package com.wzh.graph.controller;

import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import com.wzh.common.UserContext;
import com.wzh.config.SemanticCacheProperties;
import com.wzh.service.SemanticCacheService;
import com.wzh.utils.TokenUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * 工单按钮相关端点 (第3刀 B5).
 *
 * <p><b>端点设计</b>:
 * <ul>
 *   <li>{@code POST /api/agent/submit-ticket-for-message} — 用户点"提交工单"按钮触发.
 *       前端传 targetAssistantMessageId, 后端反查 → 校验幂等 → 占位 SUBMITTING →
 *       插入伪 user 消息"提交工单" → 复用 MainGraphSseController.chatStream 完整跑 Graph.</li>
 *   <li>{@code POST /internal/ticket/callback} — MCP 端工单成功后的事实回填.
 *       MCP 调 TicketSystem 拿到 ticketNo 后, 同步 HTTP 调本端点,
 *       后端把 ticketNo 写入老 assistant 消息的 submitted_ticket_id, 同步 +3 负反馈.</li>
 * </ul>
 *
 * <p><b>设计动机 — 为什么按钮 = 伪 user 消息 + 完整跑 Graph</b>:
 * 工单按钮场景跟"用户在对话里说提交工单"语义完全一致, 唯一差别是触发源. 把按钮点击翻译成
 * "用户发了一句提交工单", 整条工单链路 (intent / cache_check / RAG / ticket_agent / MCP /
 * TicketSystem / 答复落库) 零改动复用. 比"按钮直连 HTTP 提单 + 自拼答复消息"省下大量代码,
 * 也保证了对话工单和按钮工单的体验一致.</p>
 *
 * <p><b>设计动机 — 为什么 MCP 反向回调而非答复正则</b>:
 * 工单成功的事实 (HTTP 200 + ticketNo 非空) 在 TicketSystem 响应那一刻就已确定, 是结构化数据.
 * 让 LLM 答复决定 DB 落库, 相当于把"系统真相"交给"语言模型解读" — 方向反了. MCP 在拿到响应的
 * 那一刻直接同步回调 main 写库, 跟 LLM 完全解耦. LLM 后续怎么自然语言答复用户 (含不含工单号原文)
 * 都无所谓, 因为 DB 已经有真相了.</p>
 *
 * @author wzh
 * @since 2026-05-26 (第3刀 B5)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TicketButtonController {

    private final ChatMessageMapper chatMessageMapper;
    private final SemanticCacheService semanticCacheService;
    private final SemanticCacheProperties semanticCacheProperties;
    private final MainGraphSseController mainGraphSseController;

    /** 跟 InternalFileController 同源, 走 X-Internal-Api-Key Header 自校验. */
    @Value("${internal.api-key}")
    private String internalApiKey;

    /** SUBMITTING 占位常量, 用于幂等防重复点击. */
    private static final String SUBMITTING_PLACEHOLDER = "SUBMITTING";

    /** 按钮场景的伪 user 消息内容. 写死, 不接受前端传任何文本. */
    private static final String PSEUDO_USER_MESSAGE = "提交工单";

    /**
     * 用户点"提交工单"按钮的处理端点.
     *
     * <p>入参: {@code {"targetAssistantMessageId": Long}} — 被吐槽的那条 assistant 消息 id.</p>
     *
     * <p>处理流程:
     * <ol>
     *   <li>UserContext 校验登录</li>
     *   <li>反查 targetMsg, 校验存在 + role=assistant + submittedTicketId IS NULL</li>
     *   <li>UPDATE submitted_ticket_id='SUBMITTING' 占位 (幂等保证)</li>
     *   <li>INSERT 伪 user 消息 "提交工单" 到该 session</li>
     *   <li>构造 body (sessionId + ticketButtonTriggeredBy + message) 调 MainGraphSseController.chatStream</li>
     *   <li>返回 SseEmitter 给前端 (跟正常对话同样流式体验)</li>
     * </ol>
     *
     * <p><b>越权防护</b>: 当前 demo 仅校验登录, 未额外校验 targetMsg 是否属于当前用户的 session.
     * 生产环境应加 chat_session.user_id == 当前 userId 的校验.</p>
     *
     * <p><b>幂等失效兜底</b>: 占位 SUBMITTING 在异常路径下可能卡住 (Graph 跑挂 / MCP 没回调 / LLM 没决定调工具),
     * MainGraphSseController.handleDone 末尾会检查 — 仍是占位时回滚为 null.</p>
     */
    @PostMapping(value = "/api/agent/submit-ticket-for-message",
            produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitTicketForMessage(@RequestBody Map<String, Object> body) {
        log.info("[ticket-button] received body={}", body);

        SseEmitter emitter = new SseEmitter(300_000L);

        // 1. 登录校验
        TokenUtil.TokenInfo tokenInfo = UserContext.get();
        if (tokenInfo == null) {
            emitter.completeWithError(new RuntimeException("未登录"));
            return emitter;
        }

        // 2. 入参校验
        Object targetIdObj = body.get("targetAssistantMessageId");
        if (targetIdObj == null) {
            emitter.completeWithError(new RuntimeException("targetAssistantMessageId 不能为空"));
            return emitter;
        }
        Long targetId;
        try {
            targetId = (targetIdObj instanceof Number n) ? n.longValue()
                    : Long.parseLong(String.valueOf(targetIdObj));
        } catch (NumberFormatException e) {
            emitter.completeWithError(new RuntimeException("targetAssistantMessageId 非法: " + targetIdObj));
            return emitter;
        }

        // 3. 反查 + 幂等校验
        ChatMessage targetMsg = chatMessageMapper.selectById(targetId);
        if (targetMsg == null) {
            emitter.completeWithError(new RuntimeException("消息不存在: id=" + targetId));
            return emitter;
        }
        if (!"assistant".equals(targetMsg.getRole())) {
            emitter.completeWithError(new RuntimeException("只能对 AI 答复提工单, 当前消息 role=" + targetMsg.getRole()));
            return emitter;
        }
        if (targetMsg.getSubmittedTicketId() != null) {
            emitter.completeWithError(new RuntimeException(
                    "该消息已提单或正在提单中: submittedTicketId=" + targetMsg.getSubmittedTicketId()));
            return emitter;
        }

        Long sessionId = targetMsg.getSessionId();

        // 4. 占位 SUBMITTING (幂等保证 — 用户连续点击不会触发多次提单)
        // 即使两次请求并发到这里, MyBatis-Plus updateById 是原子 SQL, 第二次 update 也会成功,
        // 但前面的 submittedTicketId IS NULL 校验已拦截了, 串行场景安全. 真正的并发竞争需要数据库
        // 行锁 (SELECT FOR UPDATE), demo 量级不做.
        try {
            chatMessageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                            .eq(ChatMessage::getId, targetId)
                            .set(ChatMessage::getSubmittedTicketId, SUBMITTING_PLACEHOLDER));
            log.info("[ticket-button] placeholder SET targetId={}", targetId);
        } catch (Exception e) {
            log.error("[ticket-button] 占位失败 targetId={}", targetId, e);
            emitter.completeWithError(e);
            return emitter;
        }

        // 5. 插入伪 user 消息 "提交工单"
        // 注意: 不调 saveUserMessage (那是 chatStream 的私有方法). 这里手动构造 ChatMessage,
        // 字段最小集合即可 (sessionId/role/content/createTime, 让 MyBatis-Plus auto-fill).
        ChatMessage pseudoUser = new ChatMessage();
        pseudoUser.setSessionId(sessionId);
        pseudoUser.setRole("user");
        pseudoUser.setContent(PSEUDO_USER_MESSAGE);
        // createTime 走 @TableField(fill=INSERT) 自动填; 其他字段保持 null
        chatMessageMapper.insert(pseudoUser);
        log.info("[ticket-button] pseudo user msg inserted id={} sessionId={}",
                pseudoUser.getId(), sessionId);

        // 6. 构造 body 调 chatStream 主流程
        // 关键参数:
        //   - sessionId: 复用 targetMsg.sessionId, 让 Graph 在原会话里跑
        //   - message: "提交工单" (chatStream 内部会读这个字段; 也用于 TicketAgentNode 构造 chatHistoryJson 末尾)
        //   - ticketButtonTriggeredBy: targetId, 让 chatStream 走 ticketButton 分支 + 透传给 MCP
        Map<String, Object> chatBody = new HashMap<>();
        chatBody.put("sessionId", sessionId);
        chatBody.put("message", PSEUDO_USER_MESSAGE);
        chatBody.put("ticketButtonTriggeredBy", targetId);
        // imageUrls 留空, regenerateFromMessageId 留空, selectedFeatureName 留空 → 都是默认值

        return mainGraphSseController.chatStream(chatBody);
    }

    /**
     * MCP 端工单成功后的事实回填端点.
     *
     * <p><b>调用方</b>: 仅 MCP 模块 (mcp.client.MainAppClient.notifyTicketCallback). 走 internal-api-key 鉴权,
     * 前端不应访问. {@code /internal/**} 路径已绕过 AuthInterceptor.</p>
     *
     * <p>入参: {@code {"targetAssistantMessageId": Long, "ticketNo": String}}</p>
     *
     * <p>处理流程:
     * <ol>
     *   <li>反查 targetMsg, 必须存在</li>
     *   <li>UPDATE submitted_ticket_id={ticketNo} (覆盖 SUBMITTING 占位 / null / 其他)</li>
     *   <li>读 targetMsg.cache_key, 非空时给该 cacheKey 累加工单负反馈分 (默认 +3)</li>
     * </ol>
     *
     * <p><b>幂等性</b>: 同一 targetId 多次回调会重复 UPDATE (最后写赢) + 重复 incrementFeedback (重复累加).
     * 后者可能导致负反馈被多算, 但 MCP 端只在工单创建成功 1 次时调一次, 不会重复调.
     * 即使因网络重试出现 2 次调用, 双倍负反馈 → 略偏负, 系统是收敛的 (达阈值自动 DEGRADE), 影响可接受.</p>
     */
    @PostMapping(value = "/internal/ticket/callback")
    public ResponseEntity<Map<String, Object>> ticketCallback(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestBody Map<String, Object> body) {
        log.info("[ticket-callback] received body={}", body);

        Map<String, Object> result = new HashMap<>();

        // 鉴权: 跟 InternalFileController 同模式, 端点内自校验.
        if (internalApiKey == null || internalApiKey.isBlank() || !internalApiKey.equals(apiKey)) {
            log.warn("[ticket-callback] 鉴权失败 apiKey 缺失或不匹配");
            result.put("success", false);
            result.put("message", "X-Internal-Api-Key 校验失败");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }

        Object targetIdObj = body.get("targetAssistantMessageId");
        Object ticketNoObj = body.get("ticketNo");
        if (targetIdObj == null || ticketNoObj == null) {
            result.put("success", false);
            result.put("message", "targetAssistantMessageId 或 ticketNo 缺失");
            return ResponseEntity.badRequest().body(result);
        }
        Long targetId;
        try {
            targetId = (targetIdObj instanceof Number n) ? n.longValue()
                    : Long.parseLong(String.valueOf(targetIdObj));
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "targetAssistantMessageId 非法");
            return ResponseEntity.badRequest().body(result);
        }
        String ticketNo = String.valueOf(ticketNoObj);
        if (ticketNo.isBlank() || "null".equals(ticketNo)) {
            result.put("success", false);
            result.put("message", "ticketNo 为空");
            return ResponseEntity.badRequest().body(result);
        }

        // 反查 targetMsg, 拿 cacheKey
        ChatMessage targetMsg = chatMessageMapper.selectById(targetId);
        if (targetMsg == null) {
            log.warn("[ticket-callback] target msg 不存在 id={}", targetId);
            result.put("success", false);
            result.put("message", "目标消息不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }

        // Step 1: 写 ticketNo (覆盖 SUBMITTING 占位)
        try {
            chatMessageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessage>()
                            .eq(ChatMessage::getId, targetId)
                            .set(ChatMessage::getSubmittedTicketId, ticketNo));
            log.info("[ticket-callback] submitted_ticket_id updated targetId={} ticketNo={}",
                    targetId, ticketNo);
        } catch (Exception e) {
            log.error("[ticket-callback] 更新 submitted_ticket_id 失败 targetId={}", targetId, e);
            result.put("success", false);
            result.put("message", "更新失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }

        // Step 2: 给 cacheKey 累加工单负反馈分 (B5 三入口之三: 工单 +3)
        // 跟点踩 +2 / regenerate +1 同样设计: cacheKey 非空才打分, 为空跳过.
        // 失败容错: incrementFeedback 内部已 try-catch.
        String cacheKey = targetMsg.getCacheKey();
        if (cacheKey != null && !cacheKey.isBlank()) {
            int weight = semanticCacheProperties.getFeedbackWeightTicket();
            semanticCacheService.incrementFeedback(cacheKey, weight);
            log.info("[ticket-callback] ticket feedback +{} applied to cacheKey={} targetId={}",
                    weight, cacheKey, targetId);
        } else {
            log.info("[ticket-callback] targetId={} cacheKey is null, skip cache feedback. " +
                    "Likely target message has no cache binding (chitchat/admin/regenerate-output).",
                    targetId);
        }

        result.put("success", true);
        result.put("ticketNo", ticketNo);
        result.put("targetAssistantMessageId", targetId);
        result.put("cacheFeedbackApplied", cacheKey != null && !cacheKey.isBlank());
        return ResponseEntity.ok(result);
    }
}