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
import com.wzh.config.SemanticCacheProperties;
import com.wzh.service.SemanticCacheService;
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
    private final SemanticCacheService semanticCacheService;
    private final SemanticCacheProperties semanticCacheProperties;

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
        // B5: 工单按钮场景标记. 非空 = 用户点了"提交工单"按钮.
        // 此时 user 消息("提交工单") 已由按钮端点 submitTicketForMessage 在调本方法前插入,
        // 老 assistant 消息已被占位 submitted_ticket_id='SUBMITTING'.
        // 普通对话 chatStream POST 不传, 保持 null.
        Long ticketButtonTriggeredBy = body.get("ticketButtonTriggeredBy") == null ? null
                : toLong(body.get("ticketButtonTriggeredBy"));

        // 2. session 处理
        Long sessionId;
        Long currentUserMessageId;
        // B3-c: regenerate 场景的老答案 cacheKey, 仅在 regenerate 分支非空; 普通对话保持 null.
        // 用途 1: 跑 Graph 前 incrementFeedback(+1) 给老答案打负反馈分.
        // 用途 2: 不直接影响 initial state, 因为 IS_REGENERATE 标记本身已足够让 CacheCheckNode 跳过.
        String regenerateOldCacheKey = null;
        if (regenerateFromMessageId != null) {
            // ===== regenerate 分支: 反查 user 消息 + 删旧 assistant + 不插入新 user 消息 =====
            RegenerateContext rc = resolveRegenerateContext(regenerateFromMessageId);
            sessionId = rc.sessionId;
            currentUserMessageId = rc.userMessageId;
            userMessage = rc.userContent;
            userImageUrls = rc.userImageUrls;
            regenerateOldCacheKey = rc.oldCacheKey;
            log.info("[regenerate] fromAssistantId={} → sessionId={} userMsgId={} content.len={} oldCacheKey={}",
                    regenerateFromMessageId, sessionId, currentUserMessageId,
                    userMessage == null ? 0 : userMessage.length(),
                    regenerateOldCacheKey == null ? "null" : regenerateOldCacheKey);

            // B3-c: 给老 cacheKey 累加 regenerate 负反馈分.
            // 时机: 跑 Graph 之前, 与新答案是否生成成功完全解耦. 即使 Graph 后续异常, 这个
            // 负反馈也应被记录 — 因为"用户点了重新生成" = "对老答案投了不信任票"的事实即时确定.
            // 失败容错: incrementFeedback 内部已 try/catch, 不影响主流程.
            if (regenerateOldCacheKey != null && !regenerateOldCacheKey.isBlank()) {
                semanticCacheService.incrementFeedback(
                        regenerateOldCacheKey,
                        semanticCacheProperties.getFeedbackWeightRegenerate());
                log.info("[regenerate] feedback +{} applied to oldCacheKey={}",
                        semanticCacheProperties.getFeedbackWeightRegenerate(),
                        regenerateOldCacheKey);
            }
        } else if (ticketButtonTriggeredBy != null) {
            // ===== B5 工单按钮分支: 伪 user 消息已由 submitTicketForMessage 端点插入, 这里只反查 =====
            // 注意:
            //   - 伪 user 消息内容固定为 "提交工单" (端点入口处插入时即写死)
            //   - 老 assistant 消息的 submitted_ticket_id 已被置为 'SUBMITTING' 占位
            //   - 负反馈 +3 不在这里调 — 跟 regenerate 不同, 工单成功的事实只有 MCP 回调时才确定,
            //     由 MCP → /internal/ticket/callback 链路异步打分. handleDone 时如占位还在则回滚.
            ChatMessage targetMsg = chatMessageMapper.selectById(ticketButtonTriggeredBy);
            if (targetMsg == null) {
                emitter.completeWithError(new RuntimeException(
                        "ticketButtonTriggeredBy 消息不存在: " + ticketButtonTriggeredBy));
                return emitter;
            }
            sessionId = targetMsg.getSessionId();
            // body.message 实际由端点构造时填 "提交工单", 这里再校验一下兜底
            if (userMessage == null || userMessage.isBlank()) {
                userMessage = "提交工单";
            }
            // 反查 session 里最新的 user 消息 (端点刚插的 "提交工单"). 比插入端点回传 id 更鲁棒.
            ChatMessage latestUser = chatMessageMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, sessionId)
                            .eq(ChatMessage::getRole, "user")
                            .orderByDesc(ChatMessage::getId)
                            .last("LIMIT 1"));
            currentUserMessageId = latestUser == null ? null : latestUser.getId();
            log.info("[ticket-button] targetId={} sessionId={} pseudoUserMsgId={} content='{}'",
                    ticketButtonTriggeredBy, sessionId, currentUserMessageId, userMessage);
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
        // Self-RAG 收尾 hotfix: 显式重置 MATCHED_FEATURE, 与 PHASE_LOG 同一道入口防线.
        // CompiledGraph 单例 OverAllState 跨请求复用, 若不在入口清零, 上一次请求残留的
        // matchedFeature 会穿透到本次 cache_check/feature_resolve (尤其当本次问的是库中不存在的
        // 功能、解析结果为 null 时). 空串 + 下游 isBlank() 判定, 让节点据此重新解析或走 fallback.
        // 这是双保险: CacheCheckNode 顶部也做了同样重置, 两处共同堵住残留污染.
        initial.put(GraphStateKeys.MATCHED_FEATURE, "");
        // B3-c: regenerate 标记, CacheCheckNode 据此跳过 L1/L2 查询走完整 RAG.
        // 仅 regenerate 分支才显式 put true; 普通对话不传, CacheCheckNode 走 safeBool 兜底 false.
        // 显式 put 比依赖 state 残留更鲁棒 — 即使 OverAllState 跨调用复用, 本字段每次都被本请求覆盖.
        if (regenerateFromMessageId != null) {
            initial.put(GraphStateKeys.IS_REGENERATE, true);
        } else {
            // 主动写 false 防御: CompiledGraph 单例可能残留上次的 true.
            // 与 PHASE_LOG/PHASE_LATENCIES 显式重置同样动机 (Batch 1 已修过这类问题).
            initial.put(GraphStateKeys.IS_REGENERATE, false);
        }
        // B5: 工单按钮场景标记. 透传给 TicketAgentNode.buildToolContext → McpMeta → MCP submitTicket.
        // MCP 端工单成功后用这个 id 回调 main 写 submitted_ticket_id.
        // 同样显式 put 防御 state 残留: 普通对话明确写 0L, 跟"按钮场景非空 Long"区分.
        if (ticketButtonTriggeredBy != null) {
            initial.put(GraphStateKeys.TICKET_BUTTON_TRIGGERED_BY, ticketButtonTriggeredBy);
        } else {
            initial.put(GraphStateKeys.TICKET_BUTTON_TRIGGERED_BY, 0L);
        }
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
                            // B5: ticket_agent 完成 → 捕获 IS_TICKET_RESPONSE 标记, 后续 handleDone
                            // 的 cache-write 判定据此排除工单流量. 同时覆盖对话工单和按钮工单两种场景.
                            if ("ticket_agent".equals(no.node())) {
                                boolean isTicket = RouteUtil.safeBool(no.state(),
                                        GraphStateKeys.IS_TICKET_RESPONSE, false);
                                if (isTicket) {
                                    outboundCapture.put("isTicketResponse", true);
                                    log.debug("[DEBUG] captured IS_TICKET_RESPONSE=true at node='ticket_agent'");
                                }
                            }

                            // Self-RAG: knowledge_answer 完成 → 捕获 FINAL_ANSWER + SELF_RAG_SKIP_CACHE.
                            //
                            // 关键: knowledge 路径自 Self-RAG 起改为"同步生成 + best-of-2 择优", 节点内不再
                            // 推流 (sink=NOOP), 故 fullAnswer (靠流式 onToken 累积) 在该路径下为空.
                            // 最终答案落在 FINAL_ANSWER state key 里, 必须在此捕获, doOnComplete 优先采用它,
                            // 否则 assistant 消息落库内容为空、缓存也写不进去.
                            //
                            // chitchat / ticket / admin 仍是流式 (fullAnswer 有值), 不捕获 finalAnswer,
                            // doOnComplete 回退用 fullAnswer.toString() — 两条路径并存互不干扰.
                            if ("knowledge_answer".equals(no.node())) {
                                String finalAns = RouteUtil.safeString(no.state(),
                                        GraphStateKeys.FINAL_ANSWER, null);
                                if (finalAns != null && !finalAns.isBlank()) {
                                    outboundCapture.put("knowledgeFinalAnswer", finalAns);
                                }
                                boolean skipCache = RouteUtil.safeBool(no.state(),
                                        GraphStateKeys.SELF_RAG_SKIP_CACHE, false);
                                outboundCapture.put("selfRagSkipCache", skipCache);
                                String verdict = RouteUtil.safeString(no.state(),
                                        GraphStateKeys.SELF_RAG_VERDICT, null);
                                log.debug("[DEBUG] captured knowledge_answer finalAnsLen={} skipCache={} verdict={}",
                                        finalAns == null ? 0 : finalAns.length(), skipCache, verdict);
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

                            // 第3刀 B3-a: cache_check 命中时立即推 meta + 捕获 cacheHit 信息
                            if ("cache_check".equals(no.node())) {
                                String hitKey = RouteUtil.safeString(no.state(),
                                        GraphStateKeys.CACHE_HIT_KEY, null);
                                String hitLayer = RouteUtil.safeString(no.state(),
                                        GraphStateKeys.CACHE_HIT_LAYER, null);
                                if (hitKey != null && !hitKey.isBlank()) {
                                    outboundCapture.put("cacheHitKey", hitKey);
                                    outboundCapture.put("cacheHitLayer", hitLayer);
                                    // 命中场景: 不会经过 merger, 直接在此推 meta
                                    if (!metaEmitted.get()) {
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
                                }
                                // 同时捕获 cache_check 写入的 matchedFeature (避免后续节点未执行时 holder 缺失)
                                String featureFromCache = RouteUtil.safeString(no.state(),
                                        GraphStateKeys.MATCHED_FEATURE, null);
                                if (featureFromCache != null && !featureFromCache.isBlank()) {
                                    outboundCapture.put("matchedFeature", featureFromCache);
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
                            Intent finalIntent = (Intent) outboundCapture.get("intent");
                            String finalFeature = (String) outboundCapture.get("matchedFeature");
                            String finalCacheHitKey = (String) outboundCapture.get("cacheHitKey");
                            String finalCacheHitLayer = (String) outboundCapture.get("cacheHitLayer");
                            // B5: ticket_agent 节点完成时被设为 true; 普通流量为 null/false.
                            Boolean finalIsTicketResponseBoxed = (Boolean) outboundCapture.get("isTicketResponse");
                            boolean finalIsTicketResponse = Boolean.TRUE.equals(finalIsTicketResponseBoxed);

                            // Self-RAG: 决定本轮最终答案内容.
                            // - knowledge 路径 (同步生成): 用捕获的 FINAL_ANSWER
                            // - chitchat/ticket/admin (流式): 回退用 fullAnswer 累积值
                            String knowledgeFinalAnswer = (String) outboundCapture.get("knowledgeFinalAnswer");
                            String finalContent = (knowledgeFinalAnswer != null && !knowledgeFinalAnswer.isBlank())
                                    ? knowledgeFinalAnswer
                                    : fullAnswer.toString();
                            Boolean skipCacheBoxed = (Boolean) outboundCapture.get("selfRagSkipCache");
                            boolean finalSelfRagSkipCache = Boolean.TRUE.equals(skipCacheBoxed);

                            // Self-RAG: knowledge 路径同步生成不推流, 此处用 replay 事件把整段答案推给前端.
                            // 当前阶段前端一次性显示 (无打字机); 前端 Batch 会把 replay 改为逐字定时播放,
                            // 实现"模拟流式", 后端无需再改. chitchat 等流式路径已通过 token 事件推完, 不重复推.
                            if (knowledgeFinalAnswer != null && !knowledgeFinalAnswer.isBlank()) {
                                try {
                                    emitter.send(SseEmitter.event().name("replay").data(knowledgeFinalAnswer));
                                } catch (Exception e) {
                                    log.warn("SSE replay 发送失败", e);
                                }
                            }

                            log.debug("[DEBUG doOnComplete] finalIntent={} finalFeature='{}' "
                                            + "cacheHitKey={} cacheHitLayer={} isTicket={} skipCache={} holder={}",
                                    finalIntent, finalFeature, finalCacheHitKey, finalCacheHitLayer,
                                    finalIsTicketResponse, finalSelfRagSkipCache, outboundCapture);

                            handleDone(
                                    emitter, sessionId,
                                    finalContent,
                                    capturedImages[0],
                                    capturedSources[0],
                                    history.size(),
                                    currentUserMessageId,
                                    finalIntent,
                                    finalFeature,
                                    finalCacheHitKey,
                                    finalCacheHitLayer,
                                    regenerateFromMessageId != null,
                                    finalIsTicketResponse,
                                    finalSelfRagSkipCache,
                                    ticketButtonTriggeredBy);
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
                            String matchedFeature,
                            String cacheHitKey,
                            String cacheHitLayer,
                            boolean isRegenerate,
                            boolean isTicketResponse,
                            boolean selfRagSkipCache,
                            Long ticketButtonTriggeredBy) {
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

            // B4: 计算 faq_hit — sources 列表中至少有一条 chunkType == "FAQ" 即视为命中.
            // 定义依据: merger 节点处理后的 sources 是真实展示给用户的来源, 这里出现 FAQ
            // 就证明 FAQ 答案被采纳入最终回答 (语义最准确, 比单纯检索召回严格).
            // SourceInfo.chunkType 在 RetrievalPostProcessor.toFaqSourceInfoList 中硬编码为 "FAQ",
            // 与 doc sources 的 chunkType (从 SearchResult 透传) 区分.
            boolean faqHit = sources != null && sources.stream()
                    .anyMatch(s -> "FAQ".equals(s.chunkType));
            msg.setFaqHit(faqHit);

            // 第3刀 B3-a: 写入 cache_key / cache_hit_layer
            // - 命中: cacheHitKey != null && cacheHitLayer != null
            // - 新写入缓存 (B3-b): cacheHitKey != null && cacheHitLayer == null  (B3-a 阶段不写)
            // - 不写缓存场景 (chitchat/admin/ticket/faqHit): 两字段都为 null
            msg.setCacheKey(cacheHitKey);
            msg.setCacheHitLayer(cacheHitLayer);

            chatMessageMapper.insert(msg);
            // MyBatis-Plus 默认自增主键回填: msg.getId() 现在已是 DB 主键

            // ============ 第3刀 B3-b: 未命中时写回缓存 ============
            // 条件 (5 项全满足才写):
            //   1. cacheHitKey == null            本次未命中 (避免重复写)
            //   2. !isRegenerate                  非重新生成场景 (用户对上一版不满意才重新生成,
            //                                                    新回答质量可能仍不稳定, 漏写比错写代价低)
            //   3. matchedFeature 非空            缓存的核心承诺是"同 feature 下的相似问题",
            //                                     feature 缺失就违背承诺
            //   4. intent 非空 && 业务意图         chitchat/admin_command/ticket 已在拓扑层短路, 但显式
            //                                     判断防御 (兜底)
            //   5. fullContent 非空                空答案不缓存
            //
            // 关于 faqHit: 之前曾有"faqHit=true 不缓存"的设计 (理由: FAQ 单独检索通路够用).
            // 实测表明 FAQ 召回后, 最终答案仍是 LLM 综合 doc+FAQ 生成的全新文本,
            // 与单一 FAQ 答案并不等价. 随着 FAQ 库扩充, 大量典型问题都会触发 FAQ 命中,
            // 不缓存等同于放弃主战场. 故去掉 faqHit 排除规则.
            //
            // 命中时 cache_key/cache_hit_layer 已在 msg.set 阶段填好;
            // 写入时仅 cache_key 有值, cache_hit_layer 保持 null (区分"命中消费" vs "新生成写回").
            //
            // B5: 加 !isTicketResponse 排除工单流量. 任何走到 ticket_agent 的响应 ("已为您提交工单 TK-xxx"
            // / "提交失败...") 都不应该进语义缓存 — 下次同 query 命中会返回过时的工单号原文,
            // 或者把 LLM 编造的 fake 工单号传染开来. 同时覆盖对话工单和按钮工单两种触发场景.
            // Self-RAG: 加 !selfRagSkipCache 排除假问题兜底话术. 当 Self-RAG 判定两版答案都不合格
            // (winner_acceptable=false), FINAL_ANSWER 被置为"没找到相关信息"的兜底话术, 此时
            // selfRagSkipCache=true. 兜底话术绝不能进缓存 — 否则下次同 query 命中会把"没找到"固化返回,
            // 与 B4 失效策略、命中即"已 PASS 答案"的缓存语义直接冲突. 这是 Self-RAG 假问题防御的闭环.
            boolean shouldCacheWrite =
                    cacheHitKey == null
                            && !isRegenerate
                            && !isTicketResponse
                            && !selfRagSkipCache
                            && matchedFeature != null && !matchedFeature.isBlank()
                            && isCacheableIntent(intent)
                            && fullContent != null && !fullContent.isBlank();

            if (shouldCacheWrite) {
                try {
                    // userMessage: 从当前 user 消息反查 (无法从 state 取, state 已被 Graph 消费完)
                    String userQuery = lookupUserQuery(currentUserMessageId);
                    if (userQuery != null && !userQuery.isBlank()) {
                        String newCacheKey = semanticCacheService.put(
                                userQuery, matchedFeature, intent,
                                fullContent,
                                sources == null ? Collections.emptyList() : sources,
                                relatedImages == null ? Collections.emptyList() : relatedImages);
                        if (newCacheKey != null) {
                            // 回填到 chat_message: cache_key=新写入的 key, cache_hit_layer 保持 null
                            chatMessageMapper.update(null,
                                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                                            .eq(ChatMessage::getId, msg.getId())
                                            .set(ChatMessage::getCacheKey, newCacheKey));
                            log.info("[cache-write] new cache written assistantMsgId={} cacheKey={} "
                                            + "feature='{}' intent={}",
                                    msg.getId(), newCacheKey, matchedFeature, intent.getCode());
                        }
                    } else {
                        log.info("[cache-write] userQuery empty, skip writing cache");
                    }
                } catch (Exception e) {
                    // 写缓存失败不影响主流程
                    log.warn("[cache-write] failed assistantMsgId={}", msg.getId(), e);
                }
            } else {
                log.info("[cache-write] skip: hit={} regen={} ticket={} skipCache={} feature='{}' intent={} faqHit={} contentLen={}",
                        cacheHitKey != null, isRegenerate, isTicketResponse, selfRagSkipCache, matchedFeature,
                        intent == null ? null : intent.getCode(), faqHit,
                        fullContent == null ? 0 : fullContent.length());
            }


            // 首轮对话自动生成标题 (history 为空时是首轮)
            if (historySize == 0) {
                autoUpdateSessionTitle(sessionId);
            }

            // B5: 工单按钮场景兜底回滚.
            //
            // 链路: 按钮端点入口处把目标 assistant 消息的 submitted_ticket_id 置 'SUBMITTING' 占位,
            // 防止用户连续点击重复提单. 正常路径下 MCP 调 TicketSystem 成功后, 同步回调 main 端
            // /internal/ticket/callback 把占位覆盖成真实 ticketNo. 但有几种异常路径会导致占位卡住:
            //   1. Graph 跑挂了, ticket_agent 根本没跑到
            //   2. LLM 没决定调 submitTicket 工具 (prompt 失效或者 ticket 路由没命中)
            //   3. TicketSystem 返回失败 (code != 200), MCP 没回调
            //   4. MCP 回调 main 时网络异常
            //
            // 兜底策略: handleDone 时检查 submitted_ticket_id, 若仍是占位 'SUBMITTING' 说明未成功
            // 收到 MCP 回调 → 回滚为 null, 让前端按钮恢复可点状态, 用户可重试.
            // 已是 'TK-...' 真实工单号的不动 (MCP 已成功回填).
            //
            // 仅工单按钮场景需要此检查; 普通对话提工单 ticketButtonTriggeredBy=null, 跳过.
            String submittedTicketIdAfterCallback = null;
            if (ticketButtonTriggeredBy != null) {
                try {
                    ChatMessage targetMsg = chatMessageMapper.selectById(ticketButtonTriggeredBy);
                    if (targetMsg != null) {
                        String currentValue = targetMsg.getSubmittedTicketId();
                        if ("SUBMITTING".equals(currentValue)) {
                            // 占位还在 → 工单未成功 → 回滚 null
                            chatMessageMapper.update(null,
                                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                                            .eq(ChatMessage::getId, ticketButtonTriggeredBy)
                                            .set(ChatMessage::getSubmittedTicketId, null));
                            log.warn("[ticket-button] handleDone rollback SUBMITTING → null targetId={} " +
                                            "(reason: MCP callback 未到达, 工单可能未成功)",
                                    ticketButtonTriggeredBy);
                        } else {
                            // 已是真实 ticketNo, MCP 回调成功
                            submittedTicketIdAfterCallback = currentValue;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ticket-button] handleDone 兜底检查失败 targetId={}",
                            ticketButtonTriggeredBy, e);
                }
            }

            // done 事件返回 assistantMessageId, 让前端知道这条新 assistant 消息的 DB 主键.
            // 前端据此把 id 挂到本地 message 对象上, 后续点赞/点踩/重新生成才能定位到具体消息.
            // 此前 done.data 为空串, 导致刚生成的消息无法立即接收反馈 (只有刷新页面后才行).
            //
            // B5: 工单按钮场景额外返回 targetAssistantMessageId + submittedTicketId, 让前端把
            // 老消息标记为已提单 + 按钮置灰显示工单号. submittedTicketId 为 null 时前端按钮保持可点.
            Map<String, Object> doneBody = new java.util.HashMap<>();
            doneBody.put("assistantMessageId", msg.getId());
            if (ticketButtonTriggeredBy != null) {
                doneBody.put("targetAssistantMessageId", ticketButtonTriggeredBy);
                doneBody.put("submittedTicketId", submittedTicketIdAfterCallback);
            }
            String doneJson = objectMapper.writeValueAsString(doneBody);
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

        // B3-c: 在删旧 assistant 前读出 cache_key, 用于后续 regenerate 负反馈打分.
        // 必须在 deleteById 之前读 — 删除后无法再 select.
        String oldCacheKey = assistantMsg.getCacheKey();

        // 物理删除老 assistant 消息. 注意: chat_message 表无 deleted 字段 (无软删约定).
        chatMessageMapper.deleteById(assistantMessageId);

        RegenerateContext ctx = new RegenerateContext();
        ctx.sessionId = sessionId;
        ctx.userMessageId = userMsg.getId();
        ctx.userContent = userMsg.getContent();
        ctx.userImageUrls = imageUrls;
        ctx.oldCacheKey = oldCacheKey;
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
        /**
         * 被删除的旧 assistant 消息的 cache_key (B3-c 新增).
         * <p>非空 = 老答案是缓存关联的 (要么命中缓存产生, 要么命中后被写入缓存).
         * 用于在跑 Graph 前给该 cacheKey 累加 regenerate 负反馈分.</p>
         * <p>null = 老答案没绑缓存 (chitchat/admin/未缓存意图), 无 cacheKey 可惩罚, 直接跳过.</p>
         */
        String oldCacheKey;
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

    /**
     * 第3刀 B3-b: 判断 intent 是否值得缓存.
     *
     * <p>当前业务意图全部缓存: HOW_TO / TROUBLESHOOT / FEATURE_INTRO / DEFAULT.
     * 不缓存意图: CHITCHAT (短路, 不会走到这) / ADMIN_COMMAND (短路) / 显式排除的特殊意图.</p>
     *
     * <p>采用白名单写法防御未来新增 Intent 时默认行为偏激进 (新增意图默认走缓存).
     * 现在的所有"业务意图"都明确列出, 新增意图必须手动加入白名单才会被缓存.</p>
     */
    private boolean isCacheableIntent(Intent intent) {
        if (intent == null) return false;
        return switch (intent) {
            case HOW_TO, TROUBLESHOOT, FEATURE_INTRO, DEFAULT -> true;
            default -> false;
        };
    }

    /**
     * 第3刀 B3-b: 反查 user 消息原文 (写缓存时用 query 算 cacheKey).
     *
     * <p>不能直接从 state 取: handleDone 在 Graph stream 完成后调, state 已被消费.
     * 走 DB 反查一次, 代价小 (单行索引查询).</p>
     */
    private String lookupUserQuery(Long userMessageId) {
        if (userMessageId == null) return null;
        try {
            ChatMessage m = chatMessageMapper.selectById(userMessageId);
            return m == null ? null : m.getContent();
        } catch (Exception e) {
            log.warn("[cache-write] lookupUserQuery failed userMessageId={}", userMessageId, e);
            return null;
        }
    }
}