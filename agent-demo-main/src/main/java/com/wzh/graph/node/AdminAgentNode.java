package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.ChatClientInvoker;
import com.wzh.graph.support.GraphMetricsCollector;
import com.wzh.graph.support.RouteUtil;
import com.wzh.graph.support.TokenSinkRegistry;
import com.wzh.graph.support.TokenStreamSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员 Agent 节点 (3.C 升级: 双模式 + history).
 *
 * @author wzh
 * @since 2026-05-12 (3.C)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAgentNode extends AbstractGraphNode {

    private static final String NODE_ID = "admin_agent";

    private static final String ADMIN_SYSTEM_PROMPT = """
            你是知识库管理员助手。当前用户是管理员, 正在查询/操作知识库元数据。
            
            === 你拥有的管理员工具 ===
            
            【listDocumentStatus】— 查询所有文档/视频学习状态
            返回数据处理要求 (必须严格执行):
            - 工具返回 documents 数组和 videos 数组, 必须将每一条记录都逐条列出, 不得只汇报数量
            - 文档列表格式: 序号. 【文档ID: {id}】{featureName} — {status}
            - 视频列表格式: 序号. 【视频ID: {id}】{name} — {status}(所属功能ID: {featureId})
            - 最后输出汇总: 共 N 篇文档(已学习 X 篇, 未学习 Y 篇); 共 M 个视频(已学习 A 个, 未学习 B 个)
            
            【triggerKnowledgeLearning】— 触发知识库学习
            - scope: "all_unlearned"=学习所有未学习; "doc_{id}"=学习指定文档; "video_{id}"=学习指定视频
            - 异步任务: 触发后立即告知 "已触发, 正在后台执行", 不要让用户等待
            
            【analyzeUsageStats】— 使用情况统计分析
            - timeRange: this_week / last_week / this_month / last_30_days
            - 拿到统计后用自然语言组织分析报告, 重点说亮点 + 需要关注的问题
            
            【retrievalSource】— 查询会话最近一次 AI 回答的知识来源
            - 用户追问"刚才的回答来自哪篇文档/来源是什么"时调用
            - 需传入当前 sessionId
            
            === 工具失败处理规则 (B5-b-2 引入) ===
            
            工具返回的是 JSON 字符串. 如果 JSON 中 "success": false, 说明工具执行失败.
            此时你必须如实告知用户, 引用工具返回的 "message" 字段说明失败原因, 绝不能编造或猜测结果.
            
            例: 工具返回 {"success":false,"message":"数据库连接超时"}
            正确回复: 抱歉, 查询学习状态时遇到了问题:数据库连接超时, 请稍后再试。
            错误回复: 当前共有 X 篇文档...... (编造数字, 不允许)
            
            === 必须遵守 ===
            - 仅在确实需要时调用工具; 简单问候等直接回答
            - 使用中文回答
            """;

    // 第六刀 Batch 2: 改注入 adminChatClient — 仅含 listDocumentStatus / analyzeUsageStats /
    // triggerKnowledgeLearning / retrievalSource 四个管理员工具, 不含 submitTicket / queryTicketStatus,
    // 物理阻断管理员误触发工单创建.
    @Qualifier("adminChatClient")
    private final ChatClient adminChatClient;

    /** B2: token 埋点采集器, 由 ChatClientInvoker 接收 */
    private final GraphMetricsCollector metricsCollector;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        String userRole = state.value(GraphStateKeys.USER_ROLE, String.class).orElse("user");
        /*TokenStreamSink sink = state.value(GraphStateKeys.TOKEN_SINK, TokenStreamSink.class)
                .orElse(TokenStreamSink.NOOP);*/
        String execId = state.value(TokenSinkRegistry.EXECUTION_ID_KEY, String.class).orElse(null);
        TokenStreamSink sink = TokenSinkRegistry.get(execId);

        // 双重保险: 路由层兜底, 防止误路由进来
        if (!"admin".equals(userRole)) {
            String deniedAnswer = "权限不足, 此功能仅管理员可用。";
            // 流式模式下也要把这条消息推给前端, 保持协议一致
            if (sink != TokenStreamSink.NOOP) {
                sink.onToken(deniedAnswer);
            }
            Map<String, Object> partial = new HashMap<>();
            partial.put(GraphStateKeys.FINAL_ANSWER, deniedAnswer);
            log.warn("[{}] denied non-admin access (userRole={})", NODE_ID, userRole);
            appendPhaseLog(state, partial, "[" + NODE_ID + "] denied (userRole=" + userRole + ")");
            return partial;
        }

        String enhanced = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class)
                .orElse(state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse(""));
        @SuppressWarnings("unchecked")
        List<ChatMessage> history = (List<ChatMessage>) state
                .value(GraphStateKeys.HISTORY_MESSAGES).orElse(Collections.emptyList());

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ADMIN_SYSTEM_PROMPT));
        appendHistoryMessages(messages, history);
        messages.add(new UserMessage(enhanced));

        Map<String, Object> toolContext = buildToolContext(state);

        // B2: 取 intent 喂给 invoke. admin_command 流量进来 intent 是 ADMIN_COMMAND, 走 RouteUtil 解码兜底.
        Intent intent = RouteUtil.safeIntent(state);

        String answer = ChatClientInvoker.invoke(adminChatClient, new Prompt(messages),
                toolContext, sink, intent, metricsCollector);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.FINAL_ANSWER, answer);

        log.info("[{}] admin answer ({} chars, history={}, mode={})",
                NODE_ID, answer == null ? 0 : answer.length(), history.size(),
                sink == TokenStreamSink.NOOP ? "sync" : "stream");
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] admin answer (" + (answer == null ? 0 : answer.length()) + " chars, hist=" + history.size() + ")");
        return partial;
    }

    private void appendHistoryMessages(
            List<org.springframework.ai.chat.messages.Message> target,
            List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return;
        for (ChatMessage m : history) {
            if ("user".equals(m.getRole())) {
                target.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                target.add(new AssistantMessage(m.getContent()));
            }
        }
    }

    private Map<String, Object> buildToolContext(OverAllState state) {
        Object userIdObj = state.value(GraphStateKeys.USER_ID).orElse(null);
        Object userNameObj = state.value(GraphStateKeys.USER_NAME).orElse(null);
        Object sessionIdObj = state.value(GraphStateKeys.SESSION_ID).orElse(null);
        return Map.of(
                "userId", userIdObj == null ? "unknown" : String.valueOf(userIdObj),
                "userName", userNameObj == null ? "未知用户" : String.valueOf(userNameObj),
                "sessionId", sessionIdObj == null ? 0L : toLong(sessionIdObj)
        );
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return 0L; }
    }
}