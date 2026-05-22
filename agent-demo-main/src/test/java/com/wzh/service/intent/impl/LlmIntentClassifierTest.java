package com.wzh.service.intent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.config.IntentKeywordsConfig;
import com.wzh.enums.Intent;
import com.wzh.model.intent.IntentClassificationResult;
import com.wzh.service.DashScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link LlmIntentClassifier} 单元测试.
 *
 * <p>不启动 Spring, 直接 new 实例 + Mockito mock DashScopeService.
 * 验证: JSON 解析、Markdown 容错、置信度阈值、超时降级、异常降级.</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
@DisplayName("LLM 意图分类器测试")
class LlmIntentClassifierTest {

    private DashScopeService dashScopeService;
    private LlmIntentClassifier classifier;
    private IntentKeywordsConfig config;

    @BeforeEach
    void setUp() {
        this.dashScopeService = mock(DashScopeService.class);

        this.config = new IntentKeywordsConfig();
        // 默认配置: 阈值 0.6, 超时 3000ms, 启用 LLM
        IntentKeywordsConfig.Classifier classifierConfig = new IntentKeywordsConfig.Classifier();
        classifierConfig.setConfidenceThreshold(0.6);
        classifierConfig.setLlmTimeoutMs(3000);
        classifierConfig.setLlmFallbackEnabled(true);
        config.setClassifier(classifierConfig);

        this.classifier = new LlmIntentClassifier(
                dashScopeService, new ObjectMapper(), config);
    }

    // ==================== 正常 JSON 解析 ====================

    @Nested
    @DisplayName("正常 JSON 输出")
    class HappyPath {

        @Test
        @DisplayName("纯 JSON 输出 → 解析成功")
        void shouldParseCleanJson() {
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), eq("json_object"), any()))
                    .thenReturn("{\"intent\":\"how_to\",\"confidence\":0.92,\"reasoning\":\"询问操作\"}");

            IntentClassificationResult result = classifier.classify("怎么改色");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.HOW_TO);
            assertThat(result.getConfidence()).isEqualTo(0.92);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.LLM);
            assertThat(result.getReasoning()).isEqualTo("询问操作");
        }

        @Test
        @DisplayName("Markdown 包裹的 JSON → 容错解析")
        void shouldStripMarkdownFence() {
            String llmOutput = "```json\n{\"intent\":\"chitchat\",\"confidence\":1.0,\"reasoning\":\"问候\"}\n```";
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn(llmOutput);

            IntentClassificationResult result = classifier.classify("hi");
            assertThat(result.getIntent()).isEqualTo(Intent.CHITCHAT);
        }

        @Test
        @DisplayName("LLM 返回未知意图 code → 降级 FALLBACK")
        void shouldFallbackOnUnknownIntent() {
            // unknown_intent 经 @JsonCreator 容错 → DEFAULT
            // 此时 result.intent == DEFAULT, parseAndValidate 中会被识别为"无效 intent"
            // 最终降级为 FALLBACK (而非保留为 LLM source)
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn("{\"intent\":\"unknown_intent\",\"confidence\":0.9,\"reasoning\":\"x\"}");

            IntentClassificationResult result = classifier.classify("xx");
            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
        }

        @Test
        @DisplayName("调用参数: 模型固定 qwen-turbo, fmt 固定 json_object")
        void shouldUseCorrectModelAndFormat() {
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn("{\"intent\":\"how_to\",\"confidence\":0.9,\"reasoning\":\"x\"}");

            classifier.classify("怎么改色");

            ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> fmtCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sceneCaptor = ArgumentCaptor.forClass(String.class);
            verify(dashScopeService).chatOnce(
                    modelCaptor.capture(), sysCaptor.capture(), any(),
                    anyFloat(), anyInt(), fmtCaptor.capture(), sceneCaptor.capture());

            assertThat(modelCaptor.getValue()).isEqualTo("qwen-turbo");
            assertThat(fmtCaptor.getValue()).isEqualTo("json_object");
            // System Prompt 必须包含 "JSON" 关键词 (DashScope JSON Mode 要求)
            assertThat(sysCaptor.getValue()).containsIgnoringCase("JSON");
            // B2: 意图分类器必须传 intent_classify scene 常量
            assertThat(sceneCaptor.getValue()).isEqualTo("intent_classify");
        }
    }

    // ==================== 置信度阈值 ====================

    @Nested
    @DisplayName("置信度阈值")
    class ConfidenceThreshold {

        @Test
        @DisplayName("置信度 < 阈值 → 降级 FALLBACK")
        void shouldFallbackOnLowConfidence() {
            // 阈值 0.6, 返回 0.5 → 应降级
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn("{\"intent\":\"how_to\",\"confidence\":0.5,\"reasoning\":\"模糊\"}");

            IntentClassificationResult result = classifier.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
            assertThat(result.getReasoning()).contains("置信度");
        }

        @Test
        @DisplayName("置信度 = 阈值 → 不降级 (边界采纳)")
        void shouldKeepResultAtThresholdBoundary() {
            // 阈值 0.6, 返回 0.6 → 不应降级 (>= 阈值)
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn("{\"intent\":\"how_to\",\"confidence\":0.6,\"reasoning\":\"边界\"}");

            IntentClassificationResult result = classifier.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.HOW_TO);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.LLM);
        }
    }

    // ==================== 异常降级 ====================

    @Nested
    @DisplayName("异常降级")
    class FailureFallback {

        @Test
        @DisplayName("DashScope 抛 RuntimeException → 降级 FALLBACK")
        void shouldFallbackOnApiException() {
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenThrow(new RuntimeException("API 调用失败"));

            IntentClassificationResult result = classifier.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
            assertThat(result.getReasoning()).contains("LLM 调用异常");
        }

        @Test
        @DisplayName("LLM 返回非 JSON → 降级 FALLBACK")
        void shouldFallbackOnInvalidJson() {
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn("我无法分类这个查询");

            IntentClassificationResult result = classifier.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
            assertThat(result.getReasoning()).contains("JSON 解析失败");
        }

        @Test
        @DisplayName("LLM 返回空字符串 → 降级 FALLBACK")
        void shouldFallbackOnEmptyResponse() {
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn("");

            IntentClassificationResult result = classifier.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
        }

        @Test
        @DisplayName("LLM 返回缺失 intent 字段的 JSON → 降级 FALLBACK")
        void shouldFallbackOnMissingIntentField() {
            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenReturn("{\"confidence\":0.9,\"reasoning\":\"忘了说 intent\"}");

            IntentClassificationResult result = classifier.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
            assertThat(result.getReasoning()).contains("intent 字段无效");
        }
    }

    // ==================== 超时降级 ====================

    @Nested
    @DisplayName("超时降级")
    class TimeoutFallback {

        @Test
        @DisplayName("DashScope 调用超过 yml 配置的超时阈值 → 降级 FALLBACK")
        void shouldFallbackOnTimeout() {
            // 把超时设得很短 (50ms), mock 故意 sleep 500ms
            config.getClassifier().setLlmTimeoutMs(50);

            when(dashScopeService.chatOnce(any(), any(), any(), anyFloat(), anyInt(), any(), any()))
                    .thenAnswer(invocation -> {
                        Thread.sleep(500);
                        return "{\"intent\":\"how_to\",\"confidence\":0.9,\"reasoning\":\"x\"}";
                    });

            IntentClassificationResult result = classifier.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
            assertThat(result.getReasoning()).contains("超时");
        }
    }
}