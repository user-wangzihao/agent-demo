package com.wzh.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
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
     * <p>与 {@link #chatStream} 的对话主流程隔离：不带历史、不开工具、低温度、非流式、低 maxTokens。
     * 典型用途：query 改写、意图识别、JSON 输出、轻量分类等需要"稳定确定性输出"的场景。</p>
     *
     * <p><b>失败策略</b>: 抛 RuntimeException,由调用方捕获并决定降级
     * (与 {@link #getEmbeddings} 风格一致)。</p>
     *
     * @param model        模型名;为 null 或空字符串时用配置默认 chatModel (qwen-plus)
     * @param systemPrompt 系统提示词;允许为 null 表示不传 system 消息
     * @param userPrompt   用户内容;不能为空
     * @param temperature  温度,建议 0.1-0.3 (确定性场景);范围 0.0-2.0
     * @param maxTokens    最大生成 token 数,建议根据任务设定 (改写 ~200,JSON 输出 ~500)
     * @return LLM 返回的纯文本内容
     * @throws RuntimeException 调用失败 (网络异常、API 错误、空响应等)
     */
    public String chatOnce(String model, String systemPrompt, String userPrompt,
                           float temperature, int maxTokens) {
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

            GenerationParam param = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(actualModel)
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    // 不开 incrementalOutput,一次性返回
                    .build();

            Generation generation = new Generation();
            GenerationResult result = generation.call(param);

            if (result == null || result.getOutput() == null
                    || result.getOutput().getChoices() == null
                    || result.getOutput().getChoices().isEmpty()) {
                throw new RuntimeException("LLM 返回空响应 model=" + actualModel);
            }

            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            long latency = System.currentTimeMillis() - start;
            log.info("[CHAT-ONCE] model={} temp={} maxTokens={} latency={}ms inputLen={} outputLen={}",
                    actualModel, temperature, maxTokens, latency,
                    userPrompt.length(), content == null ? 0 : content.length());
            return content == null ? "" : content;

        } catch (RuntimeException e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[CHAT-ONCE] 调用失败 model={} latency={}ms err={}",
                    actualModel, latency, e.getMessage());
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[CHAT-ONCE] 调用异常 model={} latency={}ms", actualModel, latency, e);
            throw new RuntimeException("LLM 一次性调用失败: " + e.getMessage(), e);
        }
    }

    // ==================== Function Calling 第一步：工具判断（非流式）====================

    /**
     * 带工具定义的非流式调用，让模型判断是否需要调用工具
     */
    /*public ToolCallResult chatWithTools(String systemPrompt, List<Message> chatHistory,
                                        String userMessage, String toolsSchema) {
        ToolCallResult result = new ToolCallResult();
        try {
            List<Message> messages = buildMessages(systemPrompt, chatHistory, userMessage);
            List<ToolBase> tools = parseToolsFromSchema(toolsSchema);

            GenerationParam param = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getChatModel())
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .tools(tools)
                    .topP(0.8).temperature(0.3f).maxTokens(500)
                    .build();

            Generation generation = new Generation();
            GenerationResult genResult = generation.call(param);
            var message = genResult.getOutput().getChoices().get(0).getMessage();

            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                // 强转为 ToolCallFunction 以访问 function 字段
                ToolCallFunction tc = (ToolCallFunction) message.getToolCalls().get(0);
                String funcName = tc.getFunction().getName();
                String argsStr = tc.getFunction().getArguments();

                result.setHasToolCall(true);
                result.setToolCallId(tc.getId());
                result.setToolName(funcName);
                result.setArguments(objectMapper.readTree(argsStr));
                log.info("模型决定调用工具: {} 参数: {}", funcName, argsStr);
            } else {
                result.setHasToolCall(false);
                result.setDirectContent(message.getContent());
                log.info("模型直接回答，不需要调用工具");
            }
        } catch (Exception e) {
            log.error("Function Calling 调用失败", e);
            result.setHasToolCall(false);
            result.setDirectContent(null);
        }
        return result;
    }*/

    // ==================== Function Calling 第二步：工具结果回传（流式）====================

    /**
     * 将工具执行结果追加到消息链，流式获取模型最终回答
     */
    /*public void chatStreamWithToolResult(String systemPrompt, List<Message> chatHistory,
                                         String userMessage, String toolCallId, String toolName,
                                         String toolResult,
                                         Consumer<String> onToken, Consumer<String> onComplete,
                                         Consumer<Exception> onError) {
        try {
            List<Message> messages = buildMessages(systemPrompt, chatHistory, userMessage);

            // assistant 消息：告知模型它之前请求了哪个工具
            Message assistantToolMsg = Message.builder()
                    .role(Role.ASSISTANT.getValue())
                    .content("")
                    .toolCalls(List.of(buildToolCallFunction(toolCallId, toolName)))
                    .build();
            messages.add(assistantToolMsg);

            // tool 消息：工具执行结果
            Message toolResultMsg = Message.builder()
                    .role("tool")
                    .content(toolResult)
                    .toolCallId(toolCallId)
                    .name(toolName)
                    .build();
            messages.add(toolResultMsg);

            GenerationParam param = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getChatModel())
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .topP(0.8).temperature(0.7f).maxTokens(1000).incrementalOutput(true)
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
            log.error("工具结果回传流式调用失败", e);
            onError.accept(e);
        }
    }*/

    // ==================== 私有辅助方法 ====================

    private List<Message> buildMessages(String systemPrompt, List<Message> chatHistory, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
        if (chatHistory != null && !chatHistory.isEmpty()) messages.addAll(chatHistory);
        messages.add(Message.builder().role(Role.USER.getValue()).content(userMessage).build());
        return messages;
    }

    /**
     * 将 TOOLS_SCHEMA JSON 字符串解析为 SDK 的 List<ToolBase>
     *
     * TOOLS_SCHEMA 格式：
     * [{"type":"function","function":{"name":"...","description":"...","parameters":{...}}}]
     *
     * 对应 SDK 构建方式：
     * ToolFunction.builder()
     *   .function(FunctionDefinition.builder().name().description().parameters(JsonUtils.parseString()).build())
     *   .build()
     */
    /*private List<ToolBase> parseToolsFromSchema(String toolsSchema) {
        List<ToolBase> tools = new ArrayList<>();
        try {
            var jsonArray = objectMapper.readTree(toolsSchema);
            for (var toolNode : jsonArray) {
                var funcNode = toolNode.get("function");
                String name = funcNode.get("name").asText();
                String description = funcNode.get("description").asText();
                String parametersJson = objectMapper.writeValueAsString(funcNode.get("parameters"));

                // JsonUtils.parseString 返回 JsonElement，需要 .getAsJsonObject() 才能匹配 parameters(JsonObject) 参数
                JsonObject parametersObj = JsonUtils.parseString(parametersJson).getAsJsonObject();
                FunctionDefinition funcDef = FunctionDefinition.builder()
                        .name(name)
                        .description(description)
                        .parameters(parametersObj)
                        .build();

                tools.add(ToolFunction.builder().function(funcDef).build());
            }
        } catch (Exception e) {
            log.error("解析工具 Schema 失败", e);
        }
        return tools;
    }*/

    /**
     * 构建 ToolCallFunction 用于回传给模型（第二步 assistant 消息中使用）
     * 用 Gson 标准 API 构造，避免依赖 SDK 可能不存在的内部类或工具类
     */
    /*private ToolCallFunction buildToolCallFunction(String id, String name) {
        String json = "{\"id\":\"" + id + "\","
                + "\"type\":\"function\","
                + "\"function\":{\"name\":\"" + name + "\",\"arguments\":\"{}\"}}";
        return new com.google.gson.Gson().fromJson(json, ToolCallFunction.class);
    }*/
}