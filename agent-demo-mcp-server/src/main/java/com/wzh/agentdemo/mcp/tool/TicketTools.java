package com.wzh.agentdemo.mcp.tool;

import com.wzh.agentdemo.mcp.client.TicketSystemClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 工单相关工具（2 个）：
 *   submitTicket        — 提交工单（需要用户上下文，从 McpMeta 取）
 *   queryTicketStatus   — 查询工单状态
 *
 * 注意：这里使用 @McpTool 注解（来自 org.springaicommunity.mcp.annotation），
 * 而不是 Spring AI 主库的 @Tool，因为 @McpTool 支持 McpMeta 特殊参数自动注入。
 * @McpMeta 参数不会出现在工具的 JSON schema 里，大模型看不到也填不了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketTools {

    private final TicketSystemClient ticketSystemClient;

    @McpTool(
            name = "submitTicket",
            description = """
                    当用户明确要求转交技术人员处理、或当前问题超出知识库范围无法回答时，调用此工具创建工单。
                    调用前先向用户确认是否愿意转交人工，获得同意后再调用。
                    
                    模型只需填写 title / description / priority 三个参数，
                    userId / userName / sessionId 会由系统自动从上下文注入，模型无需关心。
                    """
    )
    public String submitTicket(
            @McpToolParam(description = "工单标题，一句话概括用户核心问题，不超过 50 字", required = true)
            String title,

            @McpToolParam(description = "问题详细描述，包括现象、已尝试操作、期望结果", required = true)
            String description,

            @McpToolParam(description = "优先级：LOW(低) / NORMAL(普通) / HIGH(高) / URGENT(紧急)", required = true)
            String priority,

            // McpMeta 是特殊参数，不会出现在工具 schema 里，由 MCP 框架自动从客户端 meta 注入
            McpMeta meta
    ) {
        // 从 meta 中取出主应用透传的上下文
        String userId = getString(meta, "userId", "unknown");
        String userName = getString(meta, "userName", "未知用户");
        Long sessionId = getLong(meta, "sessionId", 0L);

        log.info("[MCP Tool] submitTicket title={}, userId={}, sessionId={}",
                title, userId, sessionId);

        return ticketSystemClient.submitTicket(
                title, description, priority, userId, userName, sessionId);
    }

    @McpTool(
            name = "queryTicketStatus",
            description = """
                    根据工单号查询工单当前状态和处理进展。
                    用户询问自己之前提交的工单进度时调用。
                    """
    )
    public String queryTicketStatus(
            @McpToolParam(description = "工单号，格式如 TK20250420001", required = true)
            String ticketNo
    ) {
        log.info("[MCP Tool] queryTicketStatus ticketNo={}", ticketNo);
        return ticketSystemClient.queryTicketStatus(ticketNo);
    }

    // ==================== 辅助方法 ====================

    private String getString(McpMeta meta, String key, String defaultValue) {
        if (meta == null) return defaultValue;
        Object v = meta.get(key);
        return v == null ? defaultValue : String.valueOf(v);
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