package com.wzh.graph.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

/**
 * ChatClient 双模式调用 helper (3.C 引入).
 *
 * <p><b>职责</b>: 统一处理 Answer Node 在 "同步 (.call) / 流式 (.stream + sink)" 之间的切换,
 * 避免 4 个 Node 复制相同的判断逻辑.</p>
 *
 * <p><b>行为</b>:
 * <ul>
 *   <li>sink == NOOP → 走 {@code .call().content()}, 一次性返回完整字符串</li>
 *   <li>sink != NOOP → 走 {@code .stream().content()}, 每个 token push 到 sink, 最后聚合返回</li>
 * </ul></p>
 *
 * <p><b>FINAL_ANSWER 一致性</b>: 不管哪种模式, 返回值都是完整的答案字符串,
 * Node 直接 put 到 FINAL_ANSWER state key. 这样 FinalizeNode / DB 落库逻辑零差异.</p>
 *
 * @author wzh
 * @since 2026-05-12
 */
public final class ChatClientInvoker {

    private ChatClientInvoker() {}

    /**
     * 执行 ChatClient 调用并返回完整答案字符串.
     *
     * @param chatClient  Spring AI ChatClient (第六刀 Batch 2 起按场景分立: chitchat/knowledge/ticket/admin)
     * @param prompt      构造好的 Prompt (含 system + history + user messages)
     * @param toolContext MCP _meta 透传上下文 (userId / userName / sessionId)
     * @param sink        token sink, NOOP 表示同步模式
     * @return 完整答案字符串
     */
    public static String invoke(ChatClient chatClient,
                                Prompt prompt,
                                Map<String, Object> toolContext,
                                TokenStreamSink sink) {
        if (sink == null || sink == TokenStreamSink.NOOP) {
            // 同步模式 (兼容 /api/graph/chat 端点)
            return chatClient.prompt(prompt)
                    .toolContext(toolContext)
                    .call()
                    .content();
        }
        // 流式模式 (/api/graph/chat-stream 端点)
        StringBuilder full = new StringBuilder();
        chatClient.prompt(prompt)
                .toolContext(toolContext)
                .stream()
                .content()
                .doOnNext(delta -> {
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        sink.onToken(delta);
                    }
                })
                .blockLast();
        return full.toString();
    }
}