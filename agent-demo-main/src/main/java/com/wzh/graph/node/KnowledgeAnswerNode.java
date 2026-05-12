package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.ChatClientInvoker;
import com.wzh.graph.support.SystemPromptBuilder;
import com.wzh.graph.support.TokenSinkRegistry;
import com.wzh.graph.support.TokenStreamSink;
import com.wzh.service.MilvusService.SearchResult;
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
 * 知识问答节点 (3.C 升级: 双模式 + history).
 *
 * <p><b>3.C 变化</b>:
 * <ul>
 *   <li>SSE 流式模式: 经 {@link ChatClientInvoker} 自动切换</li>
 *   <li>多轮历史: 从 state 取 {@link GraphStateKeys#HISTORY_MESSAGES} 注入到 Prompt</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-12 (3.C)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeAnswerNode extends AbstractGraphNode {

    private static final String NODE_ID = "knowledge_answer";

    private final ChatClient mcpChatClient;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        // 1. 取 state
        String enhanced = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class)
                .orElse(state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse(""));
        String userRole = state.value(GraphStateKeys.USER_ROLE, String.class).orElse("user");
        Intent intent = state.value(GraphStateKeys.INTENT, Intent.class).orElse(Intent.DEFAULT);
        List<SearchResult> processedDoc = (List<SearchResult>) state
                .value(GraphStateKeys.RETRIEVED_DOC_CHUNKS).orElse(Collections.emptyList());
        List<ChatMessage> history = (List<ChatMessage>) state
                .value(GraphStateKeys.HISTORY_MESSAGES).orElse(Collections.emptyList());
        /*TokenStreamSink sink = state.value(GraphStateKeys.TOKEN_SINK, TokenStreamSink.class)
                .orElse(TokenStreamSink.NOOP);*/
        String execId = state.value(TokenSinkRegistry.EXECUTION_ID_KEY, String.class).orElse(null);
        TokenStreamSink sink = TokenSinkRegistry.get(execId);

        // 2. 拼 retrievedContext
        String retrievedContext = buildRetrievedContext(processedDoc);

        // 3. 构造 SystemPrompt
        String systemPrompt = SystemPromptBuilder.buildSystemPrompt(retrievedContext, userRole, intent);

        // 4. 构造 messages: system + history + currentUser
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        appendHistoryMessages(messages, history);
        messages.add(new UserMessage(enhanced));

        // 5. toolContext
        Map<String, Object> toolContext = buildToolContext(state);

        // 6. 调用 (双模式 by ChatClientInvoker)
        String answer = ChatClientInvoker.invoke(mcpChatClient, new Prompt(messages),
                toolContext, sink);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.FINAL_ANSWER, answer);

        log.info("[{}] chunks={} history={} promptLen={} answerLen={} mode={}",
                NODE_ID, processedDoc.size(), history.size(), systemPrompt.length(),
                answer == null ? 0 : answer.length(),
                sink == TokenStreamSink.NOOP ? "sync" : "stream");
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] chunks=" + processedDoc.size()
                        + " hist=" + history.size()
                        + " systemPrompt=" + systemPrompt.length() + "ch"
                        + " answer=" + (answer == null ? 0 : answer.length()) + "ch");
        return partial;
    }

    /**
     * 拼检索到的 chunks 为 system prompt 的 context 段 (严格复刻 AgentService 内联逻辑).
     */
    private String buildRetrievedContext(List<SearchResult> processedResults) {
        if (processedResults == null || processedResults.isEmpty()) return "";
        StringBuilder context = new StringBuilder();
        context.append("以下是从知识库中检索到的相关信息:\n\n");
        int idx = 0;
        for (SearchResult sr : processedResults) {
            if ("image_description".equals(sr.chunkType)) continue;
            idx++;
            context.append(String.format("【知识片段 %d】(来源: %s - %s, 相关度: %.2f)%n%s%n%n",
                    idx, sr.featureName, sr.chunkType, sr.score, sr.content));
        }
        return context.toString();
    }

    /**
     * 把 DB 加载的 ChatMessage 列表追加到 Spring AI messages 中.
     * <p>过滤规则与 AgentService 一致: 仅 user / assistant 两种 role.</p>
     */
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