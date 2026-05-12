package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.ChatClientInvoker;
import com.wzh.graph.support.TokenSinkRegistry;
import com.wzh.graph.support.TokenStreamSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单 Agent 节点 (3.C 升级: 双模式 + history).
 *
 * @author wzh
 * @since 2026-05-12 (3.C)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAgentNode extends AbstractGraphNode {

    private static final String NODE_ID = "ticket_agent";

    private static final String TICKET_SYSTEM_PROMPT = """
            你是一个工单处理专员。当前用户希望提交工单或查询工单进度。
            
            === 你的核心任务 ===
            1. 如果用户希望提交工单 (说了"转人工"/"提交工单"/"人工处理"等):
               - 先用一句话向用户确认: "好的,我来帮您提交工单,请稍候。"
               - 然后调用 submitTicket 工具, 把用户的完整问题描述作为工单内容传入
               - 工具返回后, 告知用户工单编号, 并提示可用工单号查询进度
            
            2. 如果用户提到工单编号 (如 "TK-xxx", "我的工单") 并询问进度:
               - 调用 queryTicketStatus 工具, 传入工单编号
               - 把工具返回的状态用自然语言告诉用户
            
            === 必须遵守 ===
            - 不要在没有工单编号的情况下调用 queryTicketStatus
            - 不要尝试自己回答用户的业务问题, 你的职责是衔接工单流程
            - 不要调用 submitTicket 之外的"创建类"工具
            
            使用中文回答, 简短专业。
            """;

    private final ChatClient mcpChatClient;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        String enhanced = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class)
                .orElse(state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse(""));
        List<ChatMessage> history = (List<ChatMessage>) state
                .value(GraphStateKeys.HISTORY_MESSAGES).orElse(Collections.emptyList());
        /*TokenStreamSink sink = state.value(GraphStateKeys.TOKEN_SINK, TokenStreamSink.class)
                .orElse(TokenStreamSink.NOOP);*/
        String execId = state.value(TokenSinkRegistry.EXECUTION_ID_KEY, String.class).orElse(null);
        TokenStreamSink sink = TokenSinkRegistry.get(execId);

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(TICKET_SYSTEM_PROMPT));
        appendHistoryMessages(messages, history);
        messages.add(new UserMessage(enhanced));

        Map<String, Object> toolContext = buildToolContext(state);

        String answer = ChatClientInvoker.invoke(mcpChatClient, new Prompt(messages),
                toolContext, sink);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.FINAL_ANSWER, answer);

        log.info("[{}] ticket answer ({} chars, history={}, mode={})",
                NODE_ID, answer == null ? 0 : answer.length(), history.size(),
                sink == TokenStreamSink.NOOP ? "sync" : "stream");
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] ticket answer (" + (answer == null ? 0 : answer.length()) + " chars, hist=" + history.size() + ")");
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