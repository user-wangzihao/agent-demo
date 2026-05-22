package com.wzh.graph.support;

import com.wzh.enums.Intent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatClient 双模式调用 helper (3.C 引入; B5-b-2 加工具异常回退; B2 加 token 埋点).
 *
 * <p><b>职责</b>: 统一处理 Answer Node 在 "同步 (.call) / 流式 (.stream + sink)" 之间的切换,
 * 避免 4 个 Node 复制相同的判断逻辑.</p>
 *
 * <p><b>行为</b>:
 * <ul>
 *   <li>sink == NOOP → 走 {@code .call().chatResponse()}, 一次性返回完整 ChatResponse</li>
 *   <li>sink != NOOP → 走 {@code .stream().chatResponse()}, 每个 chunk push token 到 sink,
 *       最后一个 chunk 带 Usage 元数据</li>
 *   <li>不论哪种模式, 末尾都从 ChatResponse 提 Usage 喂给 {@link GraphMetricsCollector#recordLlmTokens}</li>
 *   <li>RuntimeException → 返回友好提示, 不向上抛错. Graph 正常跑完 finalize 落库 + emit done</li>
 * </ul></p>
 *
 * <p><b>FINAL_ANSWER 一致性</b>: 不管哪种模式 / 是否异常, 返回值都是完整的答案字符串.</p>
 *
 * <h2>B2 变更: 签名扩展</h2>
 * <p>原签名 {@code invoke(client, prompt, toolContext, sink)} 扩为 6 参数, 末尾加:
 * <ul>
 *   <li>{@code intent} — 当前对话意图, 用作 token 指标的 intent 标签</li>
 *   <li>{@code collector} — 指标采集器, 由 Answer Node 传入 (避免本类做 Spring 注入, 保持 static)</li>
 * </ul>
 * 4 个 Answer Node 的调用点都同步更新.</p>
 *
 * <h2>B2 变更: .content() → .chatResponse()</h2>
 * <p>原写法 {@code .call().content()} 是 Spring AI 的便利方法, 直接吐 String 但丢掉了
 * ChatResponse 整个 metadata (其中包含 Usage 字段). 改为 {@code .chatResponse()} 拿完整
 * 响应, 再自己取 {@code .getResult().getOutput().getText()} 拿文本.</p>
 *
 * <h2>B2 已知风险: 流式 token 可能未填充</h2>
 * <p>Spring AI 1.0.x 流式模式下, 不同 ChatModel 实现填充 Usage 的时机不同 — 有的在最后一个
 * chunk 填, 有的根本不填 (见 spring-ai issue #2671, #4458). 本类用 AtomicReference 持续覆盖
 * 最后一个 chunk, 如果 DashScope 不填则 token 指标会显示 0. 验证方法: 跑一轮流式对话后
 * curl /actuator/prometheus 看 {@code agent_llm_tokens_total{scene="chat_main"}} 是否非零.
 * 若为零, 下一刀补 ChatClientMessageAggregator 聚合方案.</p>
 *
 * <h2>工具异常处理三层防御 (B5-b-2 保留)</h2>
 * <ol>
 *   <li>工具内部异常 (DB 失败 / 参数解析失败): MCP Server 端 @Tool 方法 try-catch 转 JSON</li>
 *   <li>MCP Server 整体不可达 / RuntimeException: 本类捕获后返回友好提示</li>
 *   <li>LLM 看到工具错误后仍编造: Agent system prompt 显式约束</li>
 * </ol>
 *
 * @author wzh
 * @since 2026-05-12 (3.C); 2026-05-19 (B5-b-2); 2026-05-21 (B2)
 */
@Slf4j
public final class ChatClientInvoker {

    private ChatClientInvoker() {}

    /**
     * 工具调用层异常时给用户的友好提示文案.
     */
    private static final String TOOL_FAILURE_FALLBACK_MESSAGE =
            "抱歉,调用工具时遇到了问题,可能是后端服务暂时不可用,请稍后再试或联系管理员。";

    /**
     * 执行 ChatClient 调用并返回完整答案字符串. B2 起增加 token 埋点.
     *
     * @param chatClient  Spring AI ChatClient (B2 起按场景分立: chitchat/knowledge/ticket/admin)
     * @param prompt      构造好的 Prompt
     * @param toolContext MCP _meta 透传上下文
     * @param sink        token sink, NOOP 表示同步模式
     * @param intent      当前对话意图 (用于 token 指标 intent 标签); 可为 null, null 时打 "n/a"
     * @param collector   指标采集器; 可为 null, null 时跳过 token 埋点 (例如 demo/test 场景)
     * @return 完整答案字符串. 工具调用层异常时返回友好提示, 而非抛出.
     */
    public static String invoke(ChatClient chatClient,
                                Prompt prompt,
                                Map<String, Object> toolContext,
                                TokenStreamSink sink,
                                Intent intent,
                                GraphMetricsCollector collector) {
        // 流式模式下最后一个 chunk 才带 Usage; 用 AtomicReference 在 doOnNext 里持续覆盖
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
        try {
            String content;
            if (sink == null || sink == TokenStreamSink.NOOP) {
                // ============ 同步模式 ============
                ChatResponse response = chatClient.prompt(prompt)
                        .toolContext(toolContext)
                        .call()
                        .chatResponse();
                lastResponse.set(response);
                content = extractText(response);
            } else {
                // ============ 流式模式 ============
                StringBuilder full = new StringBuilder();
                chatClient.prompt(prompt)
                        .toolContext(toolContext)
                        .stream()
                        .chatResponse()
                        .doOnNext(chunk -> {
                            // 持续覆盖, 最后一个 chunk 留下来给末尾抽 Usage
                            lastResponse.set(chunk);
                            String delta = extractText(chunk);
                            if (delta != null && !delta.isEmpty()) {
                                full.append(delta);
                                sink.onToken(delta);
                            }
                        })
                        .blockLast();
                content = full.toString();
            }

            // ============ B2: token 埋点 ============
            // 放在 try 内部, return 之前; 异常分支不发指标 (失败的调用 token 信息也不准)
            recordTokensIfAvailable(lastResponse.get(), intent, collector);
            return content;

        } catch (RuntimeException e) {
            // 工具调用层异常 (MCP Server 不可达 / SSE session 失效 / 网络中断等).
            // 不向上抛, 而是把友好提示作为最终答案返回, 同时推流给前端.
            log.error("[ChatClientInvoker] 工具调用层异常, 降级为友好提示返回. " +
                    "promptSize={} streamMode={}", prompt.getInstructions().size(),
                    sink != null && sink != TokenStreamSink.NOOP, e);
            if (sink != null && sink != TokenStreamSink.NOOP) {
                try {
                    sink.onToken(TOOL_FAILURE_FALLBACK_MESSAGE);
                } catch (Exception ignored) {
                    // sink 已断开就不强推
                }
            }
            return TOOL_FAILURE_FALLBACK_MESSAGE;
        }
    }

    /**
     * 从 ChatResponse 提取 text 内容. 防御 null 链.
     */
    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 从最后一次 ChatResponse 提 Usage 喂给 metricsCollector. 任何环节 null 都静默跳过.
     *
     * <p><b>model 取值</b>: Spring AI 的 {@link ChatResponseMetadata#getModel()} 在
     * DashScope 实现下返回实际调用的模型名 (qwen-plus / qwen-turbo 等). 若 metadata 为 null
     * 或 model 字段为空, 兜底为 "unknown".</p>
     */
    private static void recordTokensIfAvailable(ChatResponse response, Intent intent,
                                                GraphMetricsCollector collector) {
        if (collector == null || response == null) return;
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) return;

        Usage usage = metadata.getUsage();
        if (usage == null) return;

        String model = metadata.getModel();
        if (model == null || model.isBlank()) model = "unknown";

        // Spring AI Usage 接口: getPromptTokens / getCompletionTokens (Integer, 可能 null)
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        long promptTokens = prompt == null ? 0L : prompt.longValue();
        long completionTokens = completion == null ? 0L : completion.longValue();

        String intentCode = (intent == null) ? "n/a" : intent.getCode();
        collector.recordLlmTokens(
                model,
                GraphMetricsCollector.MetricScene.CHAT_MAIN,
                intentCode,
                promptTokens,
                completionTokens);
    }
}
