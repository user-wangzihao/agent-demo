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
import com.wzh.config.DashScopeConfig;
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

    /**
     * 将文本转换为向量（单条）
     */
    public List<Float> getEmbedding(String text) {
        List<List<Float>> results = getEmbeddings(Collections.singletonList(text));
        return results.isEmpty() ? Collections.emptyList() : results.get(0);
    }

    /**
     * 批量将文本转换为向量
     * 通义千问 Embedding 接口单次最多处理 10 条，超过时自动分批
     */
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
                    for (Double d : item.getEmbedding()) {
                        vector.add(d.floatValue());
                    }
                    allEmbeddings.add(vector);
                }

                log.info("Embedding 批次 [{}/{}] 完成",
                        Math.min(i + batchSize, texts.size()), texts.size());
            }

            return allEmbeddings;
        } catch (Exception e) {
            log.error("调用通义千问 Embedding 接口失败", e);
            throw new RuntimeException("向量化失败: " + e.getMessage());
        }
    }

    /**
     * 流式对话 —— 逐 token 回调
     *
     * @param systemPrompt 系统提示词
     * @param chatHistory  历史对话
     * @param userMessage  用户当前输入
     * @param onToken      每收到一段文本时的回调
     * @param onComplete   全部完成时的回调（传入完整内容）
     * @param onError      出错时的回调
     */
    public void chatStream(String systemPrompt, List<Message> chatHistory, String userMessage,
                           Consumer<String> onToken, Consumer<String> onComplete, Consumer<Exception> onError) {
        try {
            List<Message> messages = new ArrayList<>();

            messages.add(Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(systemPrompt)
                    .build());

            if (chatHistory != null && !chatHistory.isEmpty()) {
                messages.addAll(chatHistory);
            }

            messages.add(Message.builder()
                    .role(Role.USER.getValue())
                    .content(userMessage)
                    .build());

            GenerationParam param = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getChatModel())
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .topP(0.8)
                    .temperature(0.7f)
                    .maxTokens(2000)
                    .incrementalOutput(true)
                    .build();

            Generation generation = new Generation();
            Flowable<GenerationResult> flowable = generation.streamCall(param);
            StringBuilder fullContent = new StringBuilder();

            flowable.blockingForEach(result -> {
                String delta = result.getOutput().getChoices().get(0).getMessage().getContent();
                if (delta != null && !delta.isEmpty()) {
                    fullContent.append(delta);
                    onToken.accept(delta);
                }
            });

            onComplete.accept(fullContent.toString());
        } catch (Exception e) {
            log.error("流式对话失败", e);
            onError.accept(e);
        }
    }
}