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
import com.wzh.graph.support.TokenSinkRegistry;
import com.wzh.graph.support.TokenStreamSink;
import com.wzh.service.AgentService.SourceInfo;
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

        // 2. session 处理
        Long sessionId = ensureSession(incomingSessionId, userId);
        Long currentUserMessageId = saveUserMessage(sessionId, userMessage, userImageUrls);

        // 3. 加载 history (排除当前用户消息)
        List<ChatMessage> history = loadHistoryExcludingCurrent(sessionId);

        // 4. 答案收集缓冲 + sources/images 捕获 (用于 done 时落库)
        StringBuilder fullAnswer = new StringBuilder();
        AtomicBoolean metaEmitted = new AtomicBoolean(false);
        @SuppressWarnings("unchecked")
        List<String>[] capturedImages = new List[]{Collections.emptyList()};
        @SuppressWarnings("unchecked")
        List<SourceInfo>[] capturedSources = new List[]{Collections.emptyList()};

        // 第五刀 hotfix: 捕获 intent 和 matchedFeature, 用于回填 chat_message.feature_name
        String[] capturedFeature = new String[]{null};
        Intent[] capturedIntent = new Intent[]{null};

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

        // 7. 异步执行 Graph stream
        new Thread(() -> {
            UserContext.set(tokenInfo);
            try {
                mainGraph.stream(initial)
                        .doOnNext(no -> {
                            // 捕获 intent / matchedFeature (用于回填 chat_message.feature_name)
                            no.state().value(GraphStateKeys.INTENT, Intent.class)
                                    .ifPresent(v -> capturedIntent[0] = v);
                            no.state().value(GraphStateKeys.MATCHED_FEATURE, String.class)
                                    .ifPresent(v -> capturedFeature[0] = v);

                            // intent 完成 → 若 chitchat, 立即推空 meta (方案 Y)
                            if ("intent".equals(no.node()) && !metaEmitted.get()) {
                                Intent intent = no.state()
                                        .value(GraphStateKeys.INTENT, Intent.class)
                                        .orElse(Intent.DEFAULT);
                                if (intent.isShortCircuit()) {
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
                        .doOnComplete(() -> handleDone(
                                emitter, sessionId,
                                fullAnswer.toString(),
                                capturedImages[0],
                                capturedSources[0],
                                history.size(),
                                currentUserMessageId,
                                capturedIntent[0],
                                capturedFeature[0]))
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
            // 第五刀 hotfix: 计算本轮的 feature_name
            // - chitchat → "Chit"
            // - matchedFeature 有值 → matchedFeature
            // - 否则 → null
            String featureName = resolveFeatureNameForMessage(intent, matchedFeature);

            // 回填当前 user 消息的 feature_name
            if (currentUserMessageId != null) {
                ChatMessage userUpdate = new ChatMessage();
                userUpdate.setId(currentUserMessageId);
                userUpdate.setFeatureName(featureName);
                chatMessageMapper.updateById(userUpdate);
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

            // 首轮对话自动生成标题 (history 为空时是首轮)
            if (historySize == 0) {
                autoUpdateSessionTitle(sessionId);
            }

            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete();
        } catch (Exception e) {
            log.error("完成处理失败", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 计算本轮消息的 feature_name 标签。
     * - 闲聊意图 → "Chit"
     * - matchedFeature 有值且非空白 → matchedFeature 本身
     * - 其他(主链路但未匹配到 feature)→ null
     */
    private String resolveFeatureNameForMessage(Intent intent, String matchedFeature) {
        if (intent != null && intent.isShortCircuit()) {
            return "Chit";
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