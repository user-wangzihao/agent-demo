package com.wzh.graph.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.entity.ChatSession;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import com.wzh.agentdemo.common.mapper.ChatSessionMapper;
import com.wzh.agentdemo.common.mapper.SysUserMapper;
import com.wzh.common.UserContext;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.SourceInfo;
import com.wzh.graph.support.RouteUtil;
import com.wzh.graph.support.TokenSinkRegistry;
import com.wzh.graph.support.TokenStreamSink;
import com.wzh.utils.TokenUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主对话 Graph 的 SSE 流式端点 (3.C 引入).
 *
 * <p><b>endpoint</b>: {@code POST /api/graph/chat-stream}</p>
 *
 * <p><b>协议对齐 AgentService</b>: 发送 4 类事件 — meta / token / done / error.
 * 事件顺序: meta → token (多次) → done. error 在异常时替代 done.</p>
 *
 * <p><b>核心机制</b>:
 * <ul>
 *   <li>通过 {@link TokenStreamSink} 注入到 state, Answer Node 推送 token 直达 SseEmitter</li>
 *   <li>通过 {@link CompiledGraph#stream(Map)} 订阅节点级 NodeOutput, 在合适的节点时机推送 meta</li>
 *   <li>chitchat 短路场景: intent 节点完成时立即推空 meta, 保证 token 流前 meta 先到</li>
 *   <li>主链路场景: merger 节点完成时推真实 meta (sources + relatedImages)</li>
 * </ul></p>
 *
 * <p><b>共存策略</b>: 同步端点 {@code /api/graph/chat} 在 {@code MainGraphController} 中保留,
 * 用于对照调试. 直到第六刀做 AgentService 下线时再决定是否合并端点.</p>
 *
 * @author wzh
 * @since 2026-05-12 (3.C)
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class MainGraphSseController {

    private final CompiledGraph mainGraph;
    private final ObjectMapper objectMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final SysUserMapper sysUserMapper;

    @PostMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> body) {
        log.info("[MainGraphSseController] received body={}", body);

        SseEmitter emitter = new SseEmitter(300_000L);
        TokenUtil.TokenInfo tokenInfo = UserContext.get();  // 跨线程传播

        // 1. 解析输入:用户身份从 UserContext(token)取
        if (tokenInfo == null) {
            emitter.completeWithError(new RuntimeException("未登录"));
            return emitter;
        }
        Long userId = tokenInfo.userId;
        String userRole = tokenInfo.role;
        String userName = lookupNickname(userId, tokenInfo.username);

        String userMessage = String.valueOf(body.getOrDefault("message", ""));
        Long incomingSessionId = body.get("sessionId") == null ? null : toLong(body.get("sessionId"));
        @SuppressWarnings("unchecked")
        List<String> userImageUrls = (List<String>) body.get("imageUrls");
        String selectedFeatureName = body.get("selectedFeatureName") == null ? null
                : String.valueOf(body.get("selectedFeatureName"));
        // 第六刀 Batch 4-4: regenerate 场景标记. 非空 = 用户点了"重新生成"按钮.
        // 此时 user 消息已在 DB 里, 旧 assistant 消息要删, message/imageUrls 反查得到.
        Long regenerateFromMessageId = body.get("regenerateFromMessageId") == null ? null
                : toLong(body.get("regenerateFromMessageId"));

        // 2. session 处理
        Long sessionId;
        Long currentUserMessageId;
        if (regenerateFromMessageId != null) {
            // ===== regenerate 分支: 反查 user 消息 + 删旧 assistant + 不插入新 user 消息 =====
            RegenerateContext rc = resolveRegenerateContext(regenerateFromMessageId);
            sessionId = rc.sessionId;
            currentUserMessageId = rc.userMessageId;
            userMessage = rc.userContent;
            userImageUrls = rc.userImageUrls;
            log.info("[regenerate] fromAssistantId={} → sessionId={} userMsgId={} content.len={}",
                    regenerateFromMessageId, sessionId, currentUserMessageId,
                    userMessage == null ? 0 : userMessage.length());
        } else {
            // ===== 普通分支: 原逻辑 =====
            sessionId = ensureSession(incomingSessionId, userId);
            currentUserMessageId = saveUserMessage(sessionId, userMessage, userImageUrls);
        }

        // 3. 加载 history (排除当前用户消息)
        List<ChatMessage> history = loadHistoryExcludingCurrent(sessionId);

        // 4. 答案收集缓冲 + sources/images 捕获 (用于 done 时落库)
        StringBuilder fullAnswer = new StringBuilder();
        AtomicBoolean metaEmitted = new AtomicBoolean(false);
        @SuppressWarnings("unchecked")
        List<String>[] capturedImages = new List[]{Collections.emptyList()};
        @SuppressWarnings("unchecked")
        List<SourceInfo>[] capturedSources = new List[]{Collections.emptyList()};

        // 第六刀 Batch 2 hotfix v5: outboundCapture 作为 doOnNext 内部的"已捕获"标记 + 字段缓存,
        // 完全由 Controller 闭包持有, 不再通过 state 透传给节点 (实测框架对 state 值做了拷贝, 节点写入无效).
        // 由 doOnNext 在各节点完成时, 从 no.state() 读取 INTENT / MATCHED_FEATURE 的 ArrayList 包装值,
        // 反序列化后存入此 holder. doOnComplete 时直接读取.
        java.util.concurrent.ConcurrentHashMap<String, Object> outboundCapture =
                new java.util.concurrent.ConcurrentHashMap<>();

        // 5. TokenStreamSink: 桥接到 SseEmitter "token" 事件
        TokenStreamSink sink = delta -> {
            fullAnswer.append(delta);
            try {
                emitter.send(SseEmitter.event().name("token").data(delta));
            } catch (Exception e) {
                log.warn("SSE token 发送失败", e);
            }
        };

        // 6. 构造 initial state
        Map<String, Object> initial = new HashMap<>();
        putIfPresent(initial, GraphStateKeys.USER_MESSAGE, userMessage);
        putIfPresent(initial, GraphStateKeys.USER_ID, userId);
        putIfPresent(initial, GraphStateKeys.USER_NAME, userName);
        putIfPresent(initial, GraphStateKeys.USER_ROLE, userRole);
        putIfPresent(initial, GraphStateKeys.SESSION_ID, sessionId);
        putIfPresent(initial, GraphStateKeys.USER_IMAGE_URLS, userImageUrls);
        putIfPresent(initial, GraphStateKeys.SELECTED_FEATURE_NAME, selectedFeatureName);
        // 3.C hotfix: sink 不进 state, 用 TokenSinkRegistry 通过 execId 绑定
        String execId = UUID.randomUUID().toString();
        TokenSinkRegistry.bind(execId, sink);
        initial.put(TokenSinkRegistry.EXECUTION_ID_KEY, execId);
        initial.put(GraphStateKeys.HISTORY_MESSAGES, history);
        // 第六刀 Batch 1: 显式重置可观测性字段,
        // 避免 CompiledGraph 单例跨调用复用 OverAllState 导致 phaseLog 累加.
        initial.put(GraphStateKeys.PHASE_LOG, new ArrayList<String>());
        initial.put(GraphStateKeys.PHASE_LATENCIES, new HashMap<String, Long>());
        // v5 起 OUTBOUND_CAPTURE 不再通过 state 透传, Controller 在 doOnNext 闭包里直接持有.

        // 7. 异步执行 Graph stream
        new Thread(() -> {
            UserContext.set(tokenInfo);
            try {
                mainGraph.stream(initial)
                        .doOnNext(no -> {
                            // ====== 诊断 DEBUG (Batch 2 hotfix v6) ======
                            // 默认 debug 级别不打印; 如需排查 Graph 跨调用 state 残留 / 枚举序列化问题,
                            // 把 application.yml 的 logging.level.com.wzh 改为 debug 即可看到.
                            Object stateIntentRaw = null;
                            Object stateFeatureRaw = null;
                            try {
                                stateIntentRaw = no.state().value(GraphStateKeys.INTENT).orElse(null);
                                stateFeatureRaw = no.state().value(GraphStateKeys.MATCHED_FEATURE).orElse(null);
                            } catch (Exception e) {
                                log.warn("[DEBUG doOnNext] read state failed", e);
                            }
                            log.debug("[DEBUG doOnNext] node='{}' state.INTENT={} state.MATCHED_FEATURE={} closureHolder={}",
                                    no.node(), stateIntentRaw, stateFeatureRaw, outboundCapture);

                            // ====== v6 关键修复: 在指定节点完成时强制覆盖, 不用 "首次捕获不变" 策略 ======
                            // 原因: CompiledGraph 是 @Bean 单例, 跨请求复用 OverAllState. 早期节点
                            // (__START__/preprocess) 的 doOnNext 会读到上一次请求残留的 INTENT/MATCHED_FEATURE,
                            // 若用 "首次捕获不变" 策略, 残留值就把本次真值挤掉了 (复现现象: 闲聊命中"快速涂色",
                            // 主链路问题命中 "Chit"). 改为只在写入该字段的节点完成时强制覆盖 holder.
                            // Batch 1 已修了 PHASE_LOG/PHASE_LATENCIES, 此处补齐 INTENT/MATCHED_FEATURE.
                            if ("intent".equals(no.node()) && stateIntentRaw != null) {
                                Intent decoded = RouteUtil.decodeIntent(stateIntentRaw);
                                if (decoded != null) {
                                    outboundCapture.put("intent", decoded);
                                    log.debug("[DEBUG] captured INTENT={} at node='intent'", decoded);
                                }
                            }
                            if ("feature_resolve".equals(no.node()) && stateFeatureRaw != null) {
                                String decoded = RouteUtil.decodeString(stateFeatureRaw);
                                if (decoded != null && !decoded.isBlank()) {
                                    outboundCapture.put("matchedFeature", decoded);
                                    log.debug("[DEBUG] captured MATCHED_FEATURE={} at node='feature_resolve'", decoded);
                                }
                            }
                            // ticket_agent 完成 → history 回溯命中的 feature 也作为兜底覆盖
                            if ("ticket_agent".equals(no.node()) && stateFeatureRaw != null) {
                                String decoded = RouteUtil.decodeString(stateFeatureRaw);
                                if (decoded != null && !decoded.isBlank()) {
                                    outboundCapture.put("matchedFeature", decoded);
                                    log.debug("[DEBUG] captured MATCHED_FEATURE={} at node='ticket_agent' (fallback)", decoded);
                                }
                            }

                            // intent 完成 → 若 chitchat / admin_command 短路立即推空 meta
                            // (这两类不经过 merger, 否则前端会等不到 meta)
                            if ("intent".equals(no.node()) && !metaEmitted.get()) {
                                Intent intent = (Intent) outboundCapture.get("intent");
                                String role = RouteUtil.safeString(no.state(),
                                        GraphStateKeys.USER_ROLE, "user");
                                if (intent != null
                                        && (RouteUtil.isChitchat(intent)
                                            || RouteUtil.isAdminCommand(intent, role))) {
                                    emitMeta(emitter, sessionId,
                                            Collections.emptyList(), Collections.emptyList());
                                    metaEmitted.set(true);
                                }
                            }
                            // merger 完成 → 主链路推真实 meta
                            else if ("merger".equals(no.node()) && !metaEmitted.get()) {
                                @SuppressWarnings("unchecked")
                                List<String> images = (List<String>) no.state()
                                        .value(GraphStateKeys.RELATED_IMAGES)
                                        .orElse(Collections.emptyList());
                                @SuppressWarnings("unchecked")
                                List<SourceInfo> sources = (List<SourceInfo>) no.state()
                                        .value(GraphStateKeys.SOURCES)
                                        .orElse(Collections.emptyList());
                                capturedImages[0] = images;
                                capturedSources[0] = sources;
                                emitMeta(emitter, sessionId, images, sources);
                                metaEmitted.set(true);
                            }
                        })
                        .doOnComplete(() -> {
                            // v4: 直接从 outboundCapture holder 读, 不依赖 NodeOutput.state() 行为
                            Intent finalIntent = (Intent) outboundCapture.get("intent");
                            String finalFeature = (String) outboundCapture.get("matchedFeature");
                            log.debug("[DEBUG doOnComplete] finalIntent={} finalFeature='{}' holder={}",
                                    finalIntent, finalFeature, outboundCapture);

                            handleDone(
                                    emitter, sessionId,
                                    fullAnswer.toString(),
                                    capturedImages[0],
                                    capturedSources[0],
                                    history.size(),
                                    currentUserMessageId,
                                    finalIntent,
                                    finalFeature);
                        })
                        .doOnError(err -> handleError(
                                emitter,
                                err instanceof Exception ? (Exception) err : new RuntimeException(err)))
                        .blockLast();
            } catch (Exception e) {
                handleError(emitter, e);
            } finally {
                TokenSinkRegistry.unbind(execId);  // hotfix: 永远释放, 防内存泄漏
                UserContext.clear();
            }
        }, "graph-sse-" + sessionId).start();

        return emitter;
    }

    /**
     * 查询用户 nickname,失败时回退到 username。
     */
    private String lookupNickname(Long userId, String fallbackUsername) {
        try {
            com.wzh.agentdemo.common.entity.SysUser user = sysUserMapper.selectById(userId);
            if (user != null && user.getNickname() != null && !user.getNickname().isBlank()) {
                return user.getNickname();
            }
        } catch (Exception e) {
            log.warn("查询 nickname 失败 userId={}", userId, e);
        }
        return fallbackUsername == null ? "" : fallbackUsername;
    }

    // ==================== meta / done / error 发送 ====================

    private void emitMeta(SseEmitter emitter, Long sessionId,
                          List<String> relatedImages, List<SourceInfo> sources) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "sessionId", sessionId,
                    "relatedImages", relatedImages,
                    "sources", sources
            ));
            emitter.send(SseEmitter.event().name("meta").data(json));
        } catch (Exception e) {
            log.warn("SSE meta 发送失败", e);
        }
    }

    private void handleDone(SseEmitter emitter, Long sessionId, String fullContent,
                            List<String> relatedImages, List<SourceInfo> sources,
                            int historySize,
                            Long currentUserMessageId,
                            Intent intent,
                            String matchedFeature) {
        try {
            // 临时 DEBUG: 看 handleDone 收到的 intent / matchedFeature 真实值
            log.debug("[DEBUG handleDone] intent={} matchedFeature='{}' currentUserMessageId={}",
                    intent, matchedFeature, currentUserMessageId);

            // 第五刀 hotfix: 计算本轮的 feature_name
            // - chitchat → "chitchat"
            // - matchedFeature 有值 → matchedFeature
            // - 否则 → null
            String featureName = resolveFeatureNameForMessage(intent, matchedFeature);

            // 回填当前 user 消息的 feature_name.
            //
            // 第六刀 Batch 4-4: 改用 LambdaUpdateWrapper.set 显式覆盖, 支持 null 写回.
            //
            // 之前的实现 (chatMessageMapper.updateById(entity)) 利用 MyBatis-Plus 的"忽略 null"
            // 默认策略 -- featureName 为 null 时跳过 update, 避免空 SET 子句非法 SQL.
            // 但这导致 regenerate 场景下: 老 user 消息 feature_name='A', 重新生成时若新结果
            // feature_name=null, 字段保留为 'A' 不会被覆盖, 破坏"feature_name 跟最新一次回答对齐"语义.
            //
            // 改为显式 set 后, null 会真实写回 DB, 行为对所有场景一致 (首轮 / 后续轮 / regenerate).
            if (currentUserMessageId != null) {
                chatMessageMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                                .eq(ChatMessage::getId, currentUserMessageId)
                                .set(ChatMessage::getFeatureName, featureName));
            }

            // 落库 assistant 消息(带 feature_name)
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole("assistant");
            msg.setContent(fullContent);
            msg.setFeatureName(featureName);
            msg.setRelatedImages(objectMapper.writeValueAsString(
                    relatedImages == null ? Collections.emptyList() : relatedImages));
            msg.setSources(objectMapper.writeValueAsString(
                    sources == null ? Collections.emptyList() : sources));
            chatMessageMapper.insert(msg);
            // MyBatis-Plus 默认自增主键回填: msg.getId() 现在已是 DB 主键

            // 首轮对话自动生成标题 (history 为空时是首轮)
            if (historySize == 0) {
                autoUpdateSessionTitle(sessionId);
            }

            // done 事件返回 assistantMessageId, 让前端知道这条新 assistant 消息的 DB 主键.
            // 前端据此把 id 挂到本地 message 对象上, 后续点赞/点踩/重新生成才能定位到具体消息.
            // 此前 done.data 为空串, 导致刚生成的消息无法立即接收反馈 (只有刷新页面后才行).
            String doneJson = objectMapper.writeValueAsString(Map.of(
                    "assistantMessageId", msg.getId()
            ));
            emitter.send(SseEmitter.event().name("done").data(doneJson));
            emitter.complete();
        } catch (Exception e) {
            log.error("完成处理失败", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 计算本轮消息的 feature_name 标签 (落库到 chat_message.feature_name).
     *
     * <p>规则:
     * <ul>
     *   <li>闲聊意图 → "chitchat"</li>
     *   <li>管理员指令意图 → "admin_command" (B5-b-1: 与 chitchat 对称, 短路链路不经过
     *       FeatureResolveNode, 没有 matchedFeature 可用)</li>
     *   <li>matchedFeature 有值且非空白 → matchedFeature 本身</li>
     *   <li>其他 (主链路但未匹配到 feature) → null</li>
     * </ul>
     */
    private String resolveFeatureNameForMessage(Intent intent, String matchedFeature) {
        if (intent != null && intent.isShortCircuit()) {
            return "chitchat";
        }
        if (intent != null && intent.isAdminCommand()) {
            return "admin_command";
        }
        if (matchedFeature != null && !matchedFeature.isBlank()) {
            return matchedFeature;
        }
        return null;
    }

    private void handleError(SseEmitter emitter, Exception error) {
        log.error("[graph-sse] 执行失败", error);
        try {
            emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    // ==================== session / history / 落库 helper ====================

    private Long ensureSession(Long incomingSessionId, Long userId) {
        if (incomingSessionId != null) return incomingSessionId;
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle("新对话");
        chatSessionMapper.insert(session);
        return session.getId();
    }

    private Long saveUserMessage(Long sessionId, String userMessage, List<String> userImageUrls) {
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        if (userImageUrls != null && !userImageUrls.isEmpty()) {
            try {
                userMsg.setUserImages(objectMapper.writeValueAsString(userImageUrls));
            } catch (Exception ignored) {}
        }
        chatMessageMapper.insert(userMsg);
        return userMsg.getId();
    }

    /**
     * 加载历史消息, 排除当前刚刚插入的 user 消息.
     * <p>逻辑严格对齐 {@code AgentService.chatStream} 的 history 切片方式.</p>
     */
    private List<ChatMessage> loadHistoryExcludingCurrent(Long sessionId) {
        List<ChatMessage> all = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreateTime));
        if (all.size() <= 1) return Collections.emptyList();
        int start = Math.max(0, all.size() - 21);
        // 最后一条是刚保存的当前 user message, 排除
        return new ArrayList<>(all.subList(start, all.size() - 1));
    }

    private void autoUpdateSessionTitle(Long sessionId) {
        try {
            List<ChatMessage> firstUser = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, sessionId)
                            .eq(ChatMessage::getRole, "user")
                            .orderByAsc(ChatMessage::getCreateTime)
                            .last("LIMIT 1"));
            if (firstUser.isEmpty()) return;
            String raw = firstUser.get(0).getContent();
            ChatSession upd = new ChatSession();
            upd.setId(sessionId);
            upd.setTitle(raw.length() > 30 ? raw.substring(0, 30) + "..." : raw);
            chatSessionMapper.updateById(upd);
        } catch (Exception e) {
            log.warn("自动更新会话标题失败 sessionId={}", sessionId, e);
        }
    }

    // ==================== 重新生成 helper (第六刀 Batch 4-4) ====================

    /**
     * regenerate 入口预处理: 反查上一条 user 消息 + 物理删除旧 assistant 消息.
     *
     * <p><b>语义</b>: 用户点了"重新生成"按钮, 期望对同一个问题重新跑 Graph 拿一个新答案.
     * 老 assistant 消息已经是"过期回答", 物理删掉; user 消息保持不变 (它仍在 DB 里).
     * Graph 跑完后, handleDone 会插入新 assistant 消息 (handleDone 路径无差异, 复用普通流程).</p>
     *
     * <p><b>失败回滚策略</b>: 不做事务. 如果 Graph 失败, 老 assistant 已被删 = 用户在该轮看到
     * "重试一次"的空白态. 用户重新发问即可, 不构成数据完整性问题. 加事务对回退价值低于复杂度.</p>
     *
     * <p><b>幂等</b>: 第二次对同一个已删消息发 regenerate 会抛"消息不存在" — 防误触.</p>
     *
     * @throws RuntimeException 消息不存在 / 非 assistant 消息 / 找不到对应 user 消息
     */
    private RegenerateContext resolveRegenerateContext(Long assistantMessageId) {
        ChatMessage assistantMsg = chatMessageMapper.selectById(assistantMessageId);
        if (assistantMsg == null || !"assistant".equals(assistantMsg.getRole())) {
            throw new RuntimeException("消息不存在或非 AI 回答");
        }
        Long sessionId = assistantMsg.getSessionId();

        // 找该 assistant 消息之前最近一条 user 消息 (id < assistantMessageId 是稳妥的偏序约束:
        // 同 session 内 id 严格递增, 比按 create_time 排序更鲁棒 — 后者偶有同毫秒并列).
        List<ChatMessage> previous = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getRole, "user")
                        .lt(ChatMessage::getId, assistantMessageId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT 1"));
        if (previous.isEmpty()) throw new RuntimeException("找不到对应的用户消息");
        ChatMessage userMsg = previous.get(0);

        // 反序列化 user_images JSON, 兼容历史脏数据 (非法 JSON 静默降级为空列表)
        List<String> imageUrls = null;
        if (userMsg.getUserImages() != null && !userMsg.getUserImages().isBlank()) {
            try {
                imageUrls = objectMapper.readValue(userMsg.getUserImages(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception ignored) {
                // ignore
            }
        }

        // 物理删除老 assistant 消息. 注意: chat_message 表无 deleted 字段 (无软删约定).
        chatMessageMapper.deleteById(assistantMessageId);

        RegenerateContext ctx = new RegenerateContext();
        ctx.sessionId = sessionId;
        ctx.userMessageId = userMsg.getId();
        ctx.userContent = userMsg.getContent();
        ctx.userImageUrls = imageUrls;
        return ctx;
    }

    /**
     * regenerate 预处理结果. 仅本类内部传值用.
     */
    private static class RegenerateContext {
        Long sessionId;
        Long userMessageId;
        String userContent;
        List<String> userImageUrls;
    }

    // ==================== 通用 ====================

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            if (value instanceof String s && s.isBlank()) return;
            map.put(key, value);
        }
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return null; }
    }
}