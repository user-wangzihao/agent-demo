package com.wzh.agentdemo.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.mcp.client.MainAppClient;
import com.wzh.agentdemo.mcp.client.TicketSystemClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 工单相关工具(2 个):
 *   submitTicket        — 提交工单(需要用户上下文,从 McpMeta 取)
 *   queryTicketStatus   — 查询工单状态
 *
 * 注意:这里使用 @McpTool 注解(来自 org.springaicommunity.mcp.annotation),
 * 而不是 Spring AI 主库的 @Tool,因为 @McpTool 支持 McpMeta 特殊参数自动注入。
 * @McpMeta 参数不会出现在工具的 JSON schema 里,大模型看不到也填不了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketTools {

    private final TicketSystemClient ticketSystemClient;
    /** B5: 工单按钮场景下回填 main 端 DB (写 submitted_ticket_id + 累加 cacheKey 负反馈分) */
    private final MainAppClient mainAppClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @McpTool(
            name = "submitTicket",
            description = """
                    当用户明确要求转交技术人员处理、或当前问题超出知识库范围无法回答时,调用此工具创建工单。
                    调用前先向用户确认是否愿意转交人工,获得同意后再调用。
                    
                    模型只需填写 title / description / priority 三个参数,
                    userId / userName / sessionId / featureName / chatHistoryJson 会由系统自动从上下文注入,模型无需关心。
                    """
    )
    public String submitTicket(
            @McpToolParam(description = "工单标题,一句话概括用户核心问题,不超过 50 字", required = true)
            String title,

            @McpToolParam(description = "问题详细描述,包括现象、已尝试操作、期望结果", required = true)
            String description,

            @McpToolParam(description = "优先级:LOW(低) / NORMAL(普通) / HIGH(高) / URGENT(紧急)", required = true)
            String priority,

            McpMeta meta
    ) {
        String userId          = getString(meta, "userId", "unknown");
        String userName        = getString(meta, "userName", "未知用户");
        Long   sessionId       = getLong  (meta, "sessionId", 0L);
        String featureName     = getString(meta, "featureName", "通用FAQ");
        // 第五刀 Batch 2:完整对话历史 JSON,供工单详情页"对话历史"卡片渲染
        String chatHistoryJson = getString(meta, "chatHistoryJson", "[]");
        // B5: 工单按钮场景下由 main 端 TicketAgentNode 透传; 普通对话提工单为 0 (= null 语义)
        Long   ticketButtonTriggeredBy = getLong(meta, "ticketButtonTriggeredBy", 0L);

        log.info("[MCP Tool] submitTicket title={}, userId={}, sessionId={}, feature={}, historyLen={}, triggeredBy={}",
                title, userId, sessionId, featureName, chatHistoryJson.length(),
                ticketButtonTriggeredBy == 0L ? "null" : ticketButtonTriggeredBy);

        // Step 1: 调 TicketSystem 创建工单. 返回的是 TicketSystem 的 Result<Map> 结构 JSON 字符串.
        String responseJson = ticketSystemClient.submitTicket(
                title, description, priority, userId, userName, sessionId, featureName, chatHistoryJson);

        // Step 2: B5 工单按钮回填 — 仅当按钮触发场景且工单提交成功时回调 main 端.
        // 设计动机: 工单成功的事实 (code=200 + ticketNo 非空) 在 TicketSystem 响应这一刻就已确定,
        // 直接由 MCP 同步回调 main 写库, 不依赖 LLM 答复文本里抠工单号. 这让 LLM 和事实层完全解耦.
        if (ticketButtonTriggeredBy != 0L) {
            String ticketNo = extractTicketNoIfSuccess(responseJson);
            if (ticketNo != null) {
                // 同步阻塞调用. 局域网 RTT < 50ms, 收益是 LLM 答复"已提交工单"时 DB 状态已一致.
                // callback 失败 (网络/main 异常) 不影响本函数返回给 LLM 的工具结果,
                // main 端 handleDone 兜底回滚 SUBMITTING 占位.
                mainAppClient.notifyTicketCallback(ticketButtonTriggeredBy, ticketNo);
            } else {
                log.warn("[MCP Tool] submitTicket 按钮场景但响应中未解析到 ticketNo, 跳过 callback. response={}",
                        responseJson);
            }
        }

        // Step 3: 不管 callback 成功失败, 都把原始 TicketSystem 响应返给 LLM, 让 LLM 自然语言告知用户.
        return responseJson;
    }

    @McpTool(
            name = "queryTicketStatus",
            description = """
                    根据工单号查询工单当前状态和处理进展。
                    用户询问自己之前提交的工单进度时调用。
                    """
    )
    public String queryTicketStatus(
            @McpToolParam(description = "工单号,格式如 TK20250420001", required = true)
            String ticketNo
    ) {
        log.info("[MCP Tool] queryTicketStatus ticketNo={}", ticketNo);
        return ticketSystemClient.queryTicketStatus(ticketNo);
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析 TicketSystem 返回的 JSON, 提取工单号. 仅在 code=200 且 data.ticketNo 非空时返回工单号,
     * 否则返回 null (调用方应跳过 callback).
     *
     * <p>TicketSystem 响应结构:
     * <pre>
     * {"code":200,"message":"success","data":{
     *   "ticketNo":"TK-20260526-0001",
     *   "ticketId":12,
     *   "status":"PENDING",
     *   "message":"工单已提交..."
     * }}
     * </pre>
     *
     * <p>异常路径: ticketSystemClient 会在 HTTP 非 2xx / 网络异常时返回
     * {@code {"success":false,"message":"..."}} 自造 JSON, 此时 root.code 字段不存在,
     * 本方法返回 null 兜底.</p>
     */
    private String extractTicketNoIfSuccess(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode codeNode = root.get("code");
            if (codeNode == null || codeNode.asInt() != 200) return null;
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) return null;
            JsonNode ticketNoNode = data.get("ticketNo");
            if (ticketNoNode == null || ticketNoNode.isNull()) return null;
            String ticketNo = ticketNoNode.asText();
            return (ticketNo == null || ticketNo.isBlank()) ? null : ticketNo;
        } catch (Exception e) {
            log.warn("[MCP Tool] 解析 TicketSystem 响应 ticketNo 失败, response={}", responseJson, e);
            return null;
        }
    }

    private String getString(McpMeta meta, String key, String defaultValue) {
        if (meta == null) return defaultValue;
        Object v = meta.get(key);
        if (v == null) return defaultValue;
        String s = String.valueOf(v);
        return s.isBlank() ? defaultValue : s;
    }

    private Long getLong(McpMeta meta, String key, Long defaultValue) {
        if (meta == null) return defaultValue;
        Object v = meta.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}