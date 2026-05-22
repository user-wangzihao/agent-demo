package com.wzh.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.ResponseFormat;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.wzh.config.DashScopeConfig;
import com.wzh.common.ToolCallResult;
import com.wzh.graph.support.GraphMetricsCollector;
import io.reactivex.Flowable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeService {

    private final DashScopeConfig dashScopeConfig;
    private final ObjectMapper objectMapper;
    /** B2: token 埋点采集器, 在 chatOnce 末尾发射 LLM token 计数. */
    private final GraphMetricsCollector metricsCollector;

    // ==================== Embedding ====================

    public List<Float> getEmbedding(String text) {
        List<List<Float>> results = getEmbeddings(Collections.singletonList(text));
        return results.isEmpty() ? Collections.emptyList() : results.get(0);
    }

    public List<List<Float>> getEmbeddings(List<String> texts) {
        try {
            List<List<Float>> allEmbeddings = new ArrayList<>();
            int batchSize = 10;
            for (int i = 0; i < texts.size(); i += batchSize) {
                List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
                TextEmbeddingParam param = TextEmbeddingParam.builder()
                        .apiKey(dashScopeConfig.getApiKey())
                        .model(dashScopeConfig.getEmbeddingModel())
                        .texts(batch)
                        .build();
                TextEmbedding textEmbedding = new TextEmbedding();
                TextEmbeddingResult result = textEmbedding.call(param);
                for (TextEmbeddingResultItem item : result.getOutput().getEmbeddings()) {
                    List<Float> vector = new ArrayList<>();
                    for (Double d : item.getEmbedding()) vector.add(d.floatValue());
                    allEmbeddings.add(vector);
                }
                log.info("Embedding 批次 [{}/{}] 完成", Math.min(i + batchSize, texts.size()), texts.size());
            }
            return allEmbeddings;
        } catch (Exception e) {
            log.error("调用通义千问 Embedding 接口失败", e);
            throw new RuntimeException("向量化失败: " + e.getMessage());
        }
    }

    // ==================== 流式对话（无工具，原有方法）====================

    public void chatStream(String systemPrompt, List<Message> chatHistory, String userMessage,
                           Consumer<String> onToken, Consumer<String> onComplete, Consumer<Exception> onError) {
        try {
            List<Message> messages = buildMessages(systemPrompt, chatHistory, userMessage);
            GenerationParam param = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getChatModel())
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .topP(0.8).temperature(0.7f).maxTokens(2000).incrementalOutput(true)
                    .build();
            Generation generation = new Generation();
            Flowable<GenerationResult> flowable = generation.streamCall(param);
            StringBuilder fullContent = new StringBuilder();
            flowable.blockingForEach(result -> {
                String delta = result.getOutput().getChoices().get(0).getMessage().getContent();
                if (delta != null && !delta.isEmpty()) { fullContent.append(delta); onToken.accept(delta); }
            });
            onComplete.accept(fullContent.toString());
        } catch (Exception e) {
            log.error("流式对话失败", e);
            onError.accept(e);
        }
    }

    // ==================== 一次性 LLM 调用（用于改写、意图识别等工具型场景）====================

    /**
     * 一次性、非流式 LLM 调用。
     *
     * <p>与 {@link #chatStream} 的对话主流程隔离:不带历史、不开工具、低温度、非流式、低 maxTokens。
     * 典型用途:query 改写、意图识别、JSON 输出、轻量分类等需要"稳定确定性输出"的场景。</p>
     *
     * <p><b>失败策略</b>: 抛 RuntimeException,由调用方捕获并决定降级
     * (与 {@link #getEmbeddings} 风格一致)。</p>
     *
     * <p><b>B2 token 埋点</b>: 方法末尾从 {@code GenerationResult.getUsage()} 提 token 数,
     * 发射到 {@link GraphMetricsCollector#recordLlmTokens}, scene 由调用方通过参数指定.
     * 调用失败分支不发射 (失败的 token 数据不准且容易污染统计).</p>
     *
     * @param model          模型名;为 null 或空字符串时用配置默认 chatModel (qwen-plus)
     * @param systemPrompt   系统提示词;允许为 null 表示不传 system 消息
     * @param userPrompt     用户内容;不能为空
     * @param temperature    温度,建议 0.1-0.3 (确定性场景);范围 0.0-2.0
     * @param maxTokens      最大生成 token 数,建议根据任务设定 (改写 ~200,JSON 输出 ~500)
     * @param responseFormat 响应格式; "json_object" 开启 JSON Mode (DashScope SDK ≥ 2.18.4 支持);
     *                       null 或其他值表示默认文本输出
     * @param scene          调用场景标识 (B2 新增), 用于 token 指标的 scene 标签.
     *                       由调用方传入对应常量, 例如 {@link GraphMetricsCollector.MetricScene#INTENT_CLASSIFY}.
     *                       为 null 时以 "chatonce_unknown" 兜底入指标.
     * @return LLM 返回的纯文本内容
     * @throws RuntimeException 调用失败 (网络异常、API 错误、空响应等)
     */
    public String chatOnce(String model, String systemPrompt, String userPrompt,
                           float temperature, int maxTokens, String responseFormat,
                           String scene) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("userPrompt 不能为空");
        }
        String actualModel = (model == null || model.trim().isEmpty())
                ? dashScopeConfig.getChatModel() : model;

        long start = System.currentTimeMillis();
        try {
            // 构造消息: system (可选) + user
            List<Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
            }
            messages.add(Message.builder().role(Role.USER.getValue()).content(userPrompt).build());

            // 构造 GenerationParam, 按需开启 JSON Mode
            GenerationParam.GenerationParamBuilder<?, ?> builder = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(actualModel)
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .temperature(temperature)
                    .maxTokens(maxTokens);
            // 不开 incrementalOutput, 一次性返回

            // JSON Mode: DashScope 官方推荐的结构化输出方式 (SDK ≥ 2.18.4)
            // prompt 里必须包含 "JSON" 关键词, 否则 API 报错
            if ("json_object".equalsIgnoreCase(responseFormat)) {
                builder.responseFormat(ResponseFormat.builder().type("json_object").build());
            }

            GenerationParam param = builder.build();
            Generation generation = new Generation();
            GenerationResult result = generation.call(param);

            if (result == null || result.getOutput() == null
                    || result.getOutput().getChoices() == null
                    || result.getOutput().getChoices().isEmpty()) {
                throw new RuntimeException("LLM 返回空响应 model=" + actualModel);
            }

            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            long latency = System.currentTimeMillis() - start;
            log.info("[CHAT-ONCE] model={} temp={} maxTokens={} fmt={} scene={} latency={}ms inputLen={} outputLen={}",
                    actualModel, temperature, maxTokens, responseFormat, scene, latency,
                    userPrompt.length(), content == null ? 0 : content.length());

            // ============ B2: token 埋点 ============
            // DashScope GenerationResult.getUsage() 返回 GenerationUsage,
            // 字段: inputTokens / outputTokens / totalTokens (Integer)
            recordTokensIfAvailable(result, actualModel, scene);

            return content == null ? "" : content;

        } catch (RuntimeException e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[CHAT-ONCE] 调用失败 model={} scene={} latency={}ms err={}",
                    actualModel, scene, latency, e.getMessage());
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[CHAT-ONCE] 调用异常 model={} scene={} latency={}ms", actualModel, scene, latency, e);
            throw new RuntimeException("LLM 一次性调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 GenerationResult 提 Usage 喂给 metricsCollector. 任何环节 null/异常都静默跳过.
     *
     * <p>这里手动用反射兼容性地取 token 字段 — DashScope SDK 不同版本字段命名略有差异
     * (有的版本叫 inputTokens, 有的叫 input_tokens), 用反射 + getter 名兜底, 防版本飘.</p>
     *
     * <p><b>B2 hotfix: completion 减法兜底</b>: DashScope JSON Mode 下偶尔
     * outputTokens 字段为 0, 但 totalTokens 仍然正确填充. 当 outputTokens=0 且
     * totalTokens > inputTokens 时, 用 (total - input) 兜底算出 completion,
     * 避免 Prometheus counter 因为 +=0 而完全不被初始化 (导致整个 completion 时序消失).</p>
     */
    private void recordTokensIfAvailable(GenerationResult result, String model, String scene) {
        if (metricsCollector == null || result == null || result.getUsage() == null) return;
        try {
            Object usage = result.getUsage();
            long promptTokens = invokeIntGetterOrZero(usage, "getInputTokens");
            long completionTokens = invokeIntGetterOrZero(usage, "getOutputTokens");
            long totalTokens = invokeIntGetterOrZero(usage, "getTotalTokens");
            // 减法兜底: JSON Mode 下 outputTokens 偶尔为 0
            if (completionTokens == 0 && totalTokens > promptTokens) {
                completionTokens = totalTokens - promptTokens;
            }
            String actualScene = (scene == null || scene.isBlank())
                    ? "chatonce_unknown" : scene;
            metricsCollector.recordLlmTokens(model, actualScene, "n/a",
                    promptTokens, completionTokens);
        } catch (Exception e) {
            log.warn("[CHAT-ONCE] token 埋点失败 model={} scene={}", model, scene, e);
        }
    }

    /**
     * 反射调用形如 getXxxTokens() 的方法, 返回 int 值; 失败/null/非 Integer 都返回 0.
     */
    private static long invokeIntGetterOrZero(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            if (value instanceof Integer i) return i.longValue();
            if (value instanceof Long l) return l;
            return 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    // ==================== 私有辅助方法 ====================

    private List<Message> buildMessages(String systemPrompt, List<Message> chatHistory, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
        if (chatHistory != null && !chatHistory.isEmpty()) messages.addAll(chatHistory);
        messages.add(Message.builder().role(Role.USER.getValue()).content(userMessage).build());
        return messages;
    }

}