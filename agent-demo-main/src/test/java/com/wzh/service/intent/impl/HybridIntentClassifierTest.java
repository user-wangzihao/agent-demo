package com.wzh.service.intent.impl;

import com.wzh.config.IntentKeywordsConfig;
import com.wzh.enums.Intent;
import com.wzh.model.intent.IntentClassificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link HybridIntentClassifier} 单元测试.
 *
 * <p>Mock 两个底层分类器, 验证协调逻辑: 关键词命中→直接返回 / 关键词未命中→走 LLM /
 * LLM 兜底关闭→直接 DEFAULT.</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
@DisplayName("混合策略分类器测试")
class HybridIntentClassifierTest {

    private KeywordIntentClassifier keywordClassifier;
    private LlmIntentClassifier llmClassifier;
    private IntentKeywordsConfig config;
    private HybridIntentClassifier hybrid;

    @BeforeEach
    void setUp() {
        this.keywordClassifier = mock(KeywordIntentClassifier.class);
        this.llmClassifier = mock(LlmIntentClassifier.class);

        this.config = new IntentKeywordsConfig();
        IntentKeywordsConfig.Classifier classifierConfig = new IntentKeywordsConfig.Classifier();
        classifierConfig.setLlmFallbackEnabled(true);
        config.setClassifier(classifierConfig);

        this.hybrid = new HybridIntentClassifier(keywordClassifier, llmClassifier, config);
    }

    // ==================== 协调流程 ====================

    @Nested
    @DisplayName("关键词优先")
    class KeywordFirst {

        @Test
        @DisplayName("关键词命中 → 直接返回, 不调 LLM")
        void shouldReturnKeywordResultWithoutCallingLlm() {
            IntentClassificationResult kwResult = IntentClassificationResult.builder()
                    .intent(Intent.HOW_TO).confidence(1.0).reasoning("关键词命中: 怎么")
                    .source(IntentClassificationResult.Source.KEYWORD).build();
            when(keywordClassifier.classify("怎么改色")).thenReturn(kwResult);

            IntentClassificationResult result = hybrid.classify("怎么改色");

            assertThat(result).isSameAs(kwResult);
            verify(llmClassifier, never()).classify(anyString());
        }
    }

    @Nested
    @DisplayName("LLM 兜底")
    class LlmFallback {

        @Test
        @DisplayName("关键词返回 null → 调 LLM, 透传结果")
        void shouldDelegateToLlmWhenKeywordReturnsNull() {
            when(keywordClassifier.classify("我想给零件改色")).thenReturn(null);

            IntentClassificationResult llmResult = IntentClassificationResult.builder()
                    .intent(Intent.HOW_TO).confidence(0.85).reasoning("隐含操作意图")
                    .source(IntentClassificationResult.Source.LLM).build();
            when(llmClassifier.classify("我想给零件改色")).thenReturn(llmResult);

            IntentClassificationResult result = hybrid.classify("我想给零件改色");

            assertThat(result).isSameAs(llmResult);
            verify(keywordClassifier).classify("我想给零件改色");
            verify(llmClassifier).classify("我想给零件改色");
        }

        @Test
        @DisplayName("LLM 自身降级返回 FALLBACK → Hybrid 透传 FALLBACK")
        void shouldPropagateLlmFallback() {
            when(keywordClassifier.classify(anyString())).thenReturn(null);
            when(llmClassifier.classify(anyString()))
                    .thenReturn(IntentClassificationResult.defaultResult("LLM 超时"));

            IntentClassificationResult result = hybrid.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
        }
    }

    @Nested
    @DisplayName("LLM 兜底开关")
    class LlmDisabled {

        @Test
        @DisplayName("yml 关闭 LLM 兜底 + 关键词未命中 → 直接 DEFAULT, 不调 LLM")
        void shouldSkipLlmWhenDisabled() {
            config.getClassifier().setLlmFallbackEnabled(false);
            when(keywordClassifier.classify(anyString())).thenReturn(null);

            IntentClassificationResult result = hybrid.classify("xxx");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
            assertThat(result.getReasoning()).contains("LLM 兜底已禁用");
            verify(llmClassifier, never()).classify(anyString());
        }

        @Test
        @DisplayName("yml 关闭 LLM 兜底, 但关键词仍命中 → 正常返回关键词结果")
        void shouldStillUseKeywordWhenLlmDisabled() {
            config.getClassifier().setLlmFallbackEnabled(false);
            IntentClassificationResult kwResult = IntentClassificationResult.builder()
                    .intent(Intent.CHITCHAT).confidence(1.0).reasoning("命中: 你好")
                    .source(IntentClassificationResult.Source.KEYWORD).build();
            when(keywordClassifier.classify("你好")).thenReturn(kwResult);

            IntentClassificationResult result = hybrid.classify("你好");

            assertThat(result).isSameAs(kwResult);
            verify(llmClassifier, never()).classify(anyString());
        }
    }

    // ==================== 边界 ====================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("空 query → 直接 DEFAULT, 不调底层任何分类器")
        void shouldShortCircuitOnEmptyQuery() {
            IntentClassificationResult result = hybrid.classify("");

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
            verify(keywordClassifier, never()).classify(anyString());
            verify(llmClassifier, never()).classify(anyString());
        }

        @Test
        @DisplayName("null query → 直接 DEFAULT, 不抛 NPE")
        void shouldHandleNullQuery() {
            IntentClassificationResult result = hybrid.classify(null);

            assertThat(result.getIntent()).isEqualTo(Intent.DEFAULT);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.FALLBACK);
        }
    }
}