package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
            
            使用中文回答, 简短专业。
            """;

    // 第六刀 Batch 2: 改注入 ticketChatClient — 仅含 submitTicket / queryTicketStatus,
    // 物理阻断工单 Agent 误调任何知识检索 / 管理类工具.
    @Qualifier("ticketChatClient")
    private final ChatClient ticketChatClient;
    private final ObjectMapper objectMapper;

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

        String answer = ChatClientInvoker.invoke(ticketChatClient, new Prompt(messages),
                toolContext, sink);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.FINAL_ANSWER, answer);

        // 第六刀 Batch 2 hotfix v2: 把 history 回溯出来的 feature 写回 partial state,
        // 让 Controller 在 doOnNext 抢救式捕获时, ticket_agent 节点完成后能从 no.state() 读到.
        // 这是给 controller 端的 decodeString 捕获用, 节点端不直接写 holder (v5 起放弃节点端写法).
        String resolvedFeature = resolveFeatureWithFallback(state);
        if (resolvedFeature != null && !resolvedFeature.isBlank()) {
            partial.put(GraphStateKeys.MATCHED_FEATURE, resolvedFeature);
        }

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildToolContext(OverAllState state) {
        Object userIdObj    = state.value(GraphStateKeys.USER_ID).orElse(null);
        Object userNameObj  = state.value(GraphStateKeys.USER_NAME).orElse(null);
        Object sessionIdObj = state.value(GraphStateKeys.SESSION_ID).orElse(null);

        // matchedFeature 解析,带 history 回溯
        String matchedFeature = resolveFeatureWithFallback(state);

        // 第五刀 Batch 2:把 history + 当前 query 序列化为 chatHistoryJson,
        // 透传给 TicketSystem 用于工单详情页"对话历史"卡片渲染
        String chatHistoryJson = buildChatHistoryJson(state);

        // Map.of 上限是 10 个键, 这里 5 个,后续如再加要换 Map.ofEntries
        return Map.of(
                "userId",          userIdObj    == null ? "unknown"  : String.valueOf(userIdObj),
                "userName",        userNameObj  == null ? "未知用户"  : String.valueOf(userNameObj),
                "sessionId",       sessionIdObj == null ? 0L         : toLong(sessionIdObj),
                "featureName",     matchedFeature == null ? "通用FAQ" : matchedFeature,
                "chatHistoryJson", chatHistoryJson
        );
    }

    /**
     * 把 history + 当前用户消息序列化为 JSON 字符串,作为工单的完整对话上下文。
     * <p>格式:{@code [{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]}
     * 末尾追加当前用户消息(history 中不含)。失败时返回 "[]"。</p>
     */
    @SuppressWarnings("unchecked")
    private String buildChatHistoryJson(OverAllState state) {
        try {
            List<ChatMessage> history = (List<ChatMessage>) state
                    .value(GraphStateKeys.HISTORY_MESSAGES).orElse(Collections.emptyList());
            String currentMessage = state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse("");

            List<Map<String, String>> messages = new ArrayList<>();
            for (ChatMessage m : history) {
                Map<String, String> msg = new HashMap<>();
                msg.put("role", m.getRole());
                msg.put("content", m.getContent());
                messages.add(msg);
            }
            // 当前 user 消息追加在末尾
            if (currentMessage != null && !currentMessage.isBlank()) {
                Map<String, String> last = new HashMap<>();
                last.put("role", "user");
                last.put("content", currentMessage);
                messages.add(last);
            }
            return objectMapper.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            log.warn("[{}] chatHistory 序列化失败, 返回空数组", NODE_ID, e);
            return "[]";
        }
    }

    /**
     * matchedFeature 解析,带 history 回溯兜底。
     * 解析顺序:
     *   1. 当前 state 的 MATCHED_FEATURE(本轮 FeatureResolveNode 输出)
     *   2. 倒序遍历 history, 找最近一条 featureName 非空且不是 "chitchat" 的消息
     *   3. 都没找到 → null (上层会兜底为 "通用FAQ")
     */
    @SuppressWarnings("unchecked")
    private String resolveFeatureWithFallback(OverAllState state) {
        // 第一层:当前轮
        String current = state.value(GraphStateKeys.MATCHED_FEATURE, String.class).orElse(null);
        if (current != null && !current.isBlank()) {
            return current;
        }
        // 第二层:回溯 history
        List<ChatMessage> history = (List<ChatMessage>) state
                .value(GraphStateKeys.HISTORY_MESSAGES).orElse(Collections.emptyList());
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            String fn = m.getFeatureName();
            if (fn != null && !fn.isBlank() && !"chitchat".equals(fn) && !"Chit".equals(fn)) {
                log.info("[{}] feature 从 history 回溯命中: {}", NODE_ID, fn);
                return fn;
            }
        }
        return null;
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return 0L; }
    }
}