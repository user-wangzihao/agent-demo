package com.wzh.graph.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

/**
 * ChatClient 双模式调用 helper (3.C 引入; B5-b-2 加工具异常回退).
 *
 * <p><b>职责</b>: 统一处理 Answer Node 在 "同步 (.call) / 流式 (.stream + sink)" 之间的切换,
 * 避免 4 个 Node 复制相同的判断逻辑.</p>
 *
 * <p><b>行为</b>:
 * <ul>
 *   <li>sink == NOOP → 走 {@code .call().content()}, 一次性返回完整字符串</li>
 *   <li>sink != NOOP → 走 {@code .stream().content()}, 每个 token push 到 sink, 最后聚合返回</li>
 *   <li>RuntimeException (含 MCP Server 不可达 / SSE session 失效 / 工具调用层 IO 异常)
 *       → 返回友好提示字符串, 不向上抛错. 让 Graph 正常跑完 finalize 落库 + emit done</li>
 * </ul></p>
 *
 * <p><b>FINAL_ANSWER 一致性</b>: 不管哪种模式 / 是否异常, 返回值都是完整的答案字符串,
 * Node 直接 put 到 FINAL_ANSWER state key. 这样 FinalizeNode / DB 落库逻辑零差异.</p>
 *
 * <h2>工具异常处理三层防御 (B5-b-2)</h2>
 * <ol>
 *   <li><b>工具内部异常</b> (DB 失败 / 参数解析失败): MCP Server 端的 @Tool 方法
 *       try-catch 转 {@code {"success":false,"message":...}} JSON. LLM 在 prompt
 *       规则约束下如实告知用户 (formaitation C)</li>
 *   <li><b>MCP Server 整体不可达 / 工具调用层 RuntimeException</b>: 本类捕获
 *       后返回友好提示字符串, 不让异常冒到 Graph 中断流程 (form B)</li>
 *   <li><b>LLM 看到工具错误后仍编造</b>: Agent system prompt 加显式规则约束
 *       (form C, 在 AdminAgentNode/KnowledgeAnswerNode/TicketAgentNode prompt 处理)</li>
 * </ol>
 *
 * @author wzh
 * @since 2026-05-12 (3.C); 2026-05-19 (B5-b-2 加工具异常回退)
 */
@Slf4j
public final class ChatClientInvoker {

    private ChatClientInvoker() {}

    /**
     * 工具调用层异常时给用户的友好提示文案.
     *
     * <p>使用场景: MCP Server 进程已宕机, 或 SSE session 失效, 或工具调用层抛 IOException.
     * 这些情况下没有任何工具 JSON 可供 LLM 解读, 直接由 Invoker 短路出友好文案.</p>
     */
    private static final String TOOL_FAILURE_FALLBACK_MESSAGE =
            "抱歉,调用工具时遇到了问题,可能是后端服务暂时不可用,请稍后再试或联系管理员。";

    /**
     * 执行 ChatClient 调用并返回完整答案字符串.
     *
     * @param chatClient  Spring AI ChatClient (第六刀 Batch 2 起按场景分立: chitchat/knowledge/ticket/admin)
     * @param prompt      构造好的 Prompt (含 system + history + user messages)
     * @param toolContext MCP _meta 透传上下文 (userId / userName / sessionId)
     * @param sink        token sink, NOOP 表示同步模式
     * @return 完整答案字符串. 工具调用层异常时返回友好提示, 而非抛出.
     */
    public static String invoke(ChatClient chatClient,
                                Prompt prompt,
                                Map<String, Object> toolContext,
                                TokenStreamSink sink) {
        try {
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
        } catch (RuntimeException e) {
            // 工具调用层异常 (MCP Server 不可达 / SSE session 失效 / 网络中断等).
            // 不向上抛, 而是把友好提示作为最终答案返回, 同时推流给前端.
            // Graph 仍正常跑完 → FinalizeNode 落库 → emitter 发 done. 协议一致.
            log.error("[ChatClientInvoker] 工具调用层异常, 降级为友好提示返回. " +
                    "promptSize={} streamMode={}", prompt.getInstructions().size(),
                    sink != null && sink != TokenStreamSink.NOOP, e);
            if (sink != null && sink != TokenStreamSink.NOOP) {
                // 流式模式下也要把这条消息推给前端, 否则前端会看到 "无任何 token" 然后跳到 done
                try {
                    sink.onToken(TOOL_FAILURE_FALLBACK_MESSAGE);
                } catch (Exception ignored) {
                    // sink 已断开就不强推
                }
            }
            return TOOL_FAILURE_FALLBACK_MESSAGE;
        }
    }
}