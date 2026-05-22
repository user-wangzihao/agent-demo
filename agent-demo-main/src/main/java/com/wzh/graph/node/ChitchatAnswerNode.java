package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
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
 * 闲聊短路节点 (3.C 升级: 双模式支持 SSE).
 *
 * <p><b>3.C 变化</b>: 通过 {@link ChatClientInvoker} 自动在 .call/.stream 之间切换,
 * 不需要在节点内做 if 判断, 也不需要持有 SseEmitter.</p>
 *
 * <p><b>不接 history</b>: 闲聊不依赖多轮上下文, 保持原设计.</p>
 *
 * @author wzh
 * @since 2026-05-12 (3.C)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChitchatAnswerNode extends AbstractGraphNode {

    private static final String NODE_ID = "chitchat_answer";

    private static final String CHITCHAT_SYSTEM_PROMPT = """
            你是一个友好的产品智能助手。
            用户当前不是在咨询产品问题,而是和你闲聊或打招呼。
            回答风格:
            1. 简短自然(1-2 句话)
            2. 友好不过度热情
            3. 不要主动转移话题
            使用中文回答。
            """;

    // 第六刀 Batch 2: 改注入 chitchatChatClient (零工具) — 原 mcpChatClient 持有全部 6 个工具,
    // 闲聊场景却把它灌进来, 既浪费 prompt token 又有越权风险, 改为零工具的专用 client.
    @Qualifier("chitchatChatClient")
    private final ChatClient chitchatChatClient;

    /** B2: token 埋点采集器, 由 ChatClientInvoker 接收 */
    private final GraphMetricsCollector metricsCollector;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state) {
        String enhanced = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class)
                .orElse(state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse(""));

        // 3.C: 取 sink (流式模式) 或 NOOP (同步模式)
        /*TokenStreamSink sink = state.value(GraphStateKeys.TOKEN_SINK, TokenStreamSink.class)
                .orElse(TokenStreamSink.NOOP);*/
        String execId = state.value(TokenSinkRegistry.EXECUTION_ID_KEY, String.class).orElse(null);
        TokenStreamSink sink = TokenSinkRegistry.get(execId);

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(CHITCHAT_SYSTEM_PROMPT));
        messages.add(new UserMessage(enhanced));

        // toolContext 闲聊场景仍可传 (即使不会调用工具, 透传无副作用)
        Map<String, Object> toolContext = buildToolContext(state);

        // B2: 取 intent 喂给 invoke. 闲聊节点理论上 intent 必然是 CHITCHAT, 但用 safeIntent 兜底防御.
        Intent intent = RouteUtil.safeIntent(state);

        String answer = ChatClientInvoker.invoke(chitchatChatClient, new Prompt(messages),
                toolContext, sink, intent, metricsCollector);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.FINAL_ANSWER, answer);
        partial.put(GraphStateKeys.RELATED_IMAGES, Collections.emptyList());
        partial.put(GraphStateKeys.SOURCES, Collections.emptyList());

        log.info("[{}] generated chitchat answer ({} chars, mode={})",
                NODE_ID, answer == null ? 0 : answer.length(),
                sink == TokenStreamSink.NOOP ? "sync" : "stream");
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] chitchat answer (" + (answer == null ? 0 : answer.length()) + " chars)");
        return partial;
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