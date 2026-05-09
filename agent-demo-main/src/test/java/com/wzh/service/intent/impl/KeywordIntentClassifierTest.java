package com.wzh.service.intent.impl;

import com.wzh.config.IntentKeywordsConfig;
import com.wzh.enums.Intent;
import com.wzh.model.intent.IntentClassificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeywordIntentClassifier} 单元测试.
 *
 * <p>不启动 Spring 容器, 直接 new 实例, 手动注入配置, 调用 init() 模拟 PostConstruct.</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
@DisplayName("关键词意图分类器测试")
class KeywordIntentClassifierTest {

    private KeywordIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        // 构造一份覆盖核心关键词的最小配置 (与 application.yml 同步, 但精简)
        IntentKeywordsConfig config = new IntentKeywordsConfig();
        Map<String, List<String>> kws = new HashMap<>();
        kws.put(Intent.HOW_TO.getCode(),         List.of("怎么", "如何", "步骤", "操作"));
        kws.put(Intent.TROUBLESHOOT.getCode(),   List.of("报错", "失败", "无法", "找不到", "不能"));
        kws.put(Intent.FEATURE_INTRO.getCode(),  List.of("是什么", "有什么用", "介绍"));
        kws.put(Intent.CHITCHAT.getCode(),       List.of("你好", "谢谢", "再见", "hello"));
        config.setKeywords(kws);

        this.classifier = new KeywordIntentClassifier(config);
        this.classifier.init(); // 模拟 @PostConstruct
    }

    // ==================== 单一命中场景 ====================

    @Nested
    @DisplayName("单一意图命中")
    class SingleHit {

        @Test
        @DisplayName("how_to: 标准操作类提问")
        void shouldHitHowTo() {
            IntentClassificationResult result = classifier.classify("贴片栏工具怎么用");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.HOW_TO);
            assertThat(result.getConfidence()).isEqualTo(1.0);
            assertThat(result.getSource()).isEqualTo(IntentClassificationResult.Source.KEYWORD);
            assertThat(result.getReasoning()).contains("怎么");
        }

        @Test
        @DisplayName("troubleshoot: 标准报错类提问")
        void shouldHitTroubleshoot() {
            IntentClassificationResult result = classifier.classify("点击应用更新报错了");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.TROUBLESHOOT);
            assertThat(result.getReasoning()).contains("报错");
        }

        @Test
        @DisplayName("feature_intro: 询问功能定义")
        void shouldHitFeatureIntro() {
            IntentClassificationResult result = classifier.classify("快速改色工具是什么");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.FEATURE_INTRO);
        }

        @Test
        @DisplayName("英文 chitchat 大小写不敏感")
        void shouldHitChitchatCaseInsensitive() {
            IntentClassificationResult result = classifier.classify("HELLO");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.CHITCHAT);
        }
    }

    // ==================== 多类冲突场景 ====================

    @Nested
    @DisplayName("多意图冲突")
    class Conflict {

        @Test
        @DisplayName("how_to + troubleshoot 冲突 → null")
        void shouldReturnNullOnConflict() {
            // "无法" 命中 troubleshoot, "操作" 命中 how_to → 冲突
            IntentClassificationResult result = classifier.classify("无法完成这个操作");

            assertThat(result)
                    .as("多意图冲突时应返回 null, 交由 LLM 仲裁")
                    .isNull();
        }

        @Test
        @DisplayName("feature_intro + how_to 冲突 → null")
        void shouldReturnNullOnComplexConflict() {
            // "是什么" 命中 feature_intro, "怎么" 命中 how_to
            IntentClassificationResult result = classifier.classify("贴片是什么? 怎么使用");
            assertThat(result).isNull();
        }
    }

    // ==================== 零命中场景 ====================

    @Nested
    @DisplayName("未命中关键词")
    class NoHit {

        @Test
        @DisplayName("纯名词 query → null")
        void shouldReturnNullForBareNoun() {
            IntentClassificationResult result = classifier.classify("贴片栏工具");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("含意但无关键词 → null")
        void shouldReturnNullForImplicitQuery() {
            IntentClassificationResult result = classifier.classify("我想给零件改色");
            assertThat(result).isNull();
        }
    }

    // ==================== 短 query 特殊处理 ====================

    @Nested
    @DisplayName("短 query (≤4 字)")
    class ShortQuery {

        @ParameterizedTest
        @ValueSource(strings = {"你好", "你好啊", "谢谢", "再见"})
        @DisplayName("短问候语优先 chitchat")
        void shouldPrioritizeChitchatForShortGreeting(String query) {
            IntentClassificationResult result = classifier.classify(query);

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.CHITCHAT);
        }

        @Test
        @DisplayName("短 query 不是 chitchat 时, 仍走通用流程")
        void shortNonChitchatStillGoesThroughGeneral() {
            // "怎么用" 是 3 字, ≤4 但不是 chitchat
            IntentClassificationResult result = classifier.classify("怎么用");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.HOW_TO);
        }
    }

    // ==================== 边界 / 健壮性 ====================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("query 前后有空格 → 正常匹配")
        void shouldTrimQuery() {
            IntentClassificationResult result = classifier.classify("   你好   ");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.CHITCHAT);
        }

        @Test
        @DisplayName("query 全大写英文 → 大小写不敏感命中")
        void shouldMatchCaseInsensitively() {
            IntentClassificationResult result = classifier.classify("Hello, anybody?");

            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.CHITCHAT);
        }

        @Test
        @DisplayName("DEFAULT 意图无关键词不会被误命中")
        void defaultIntentNeverMatched() {
            // 即使 query 包含 "default" 字面量, 也不应该命中 DEFAULT (DEFAULT 没有关键词)
            IntentClassificationResult result = classifier.classify("default 配置怎么改");
            // "怎么" 应该命中 how_to, 不会是 DEFAULT
            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.HOW_TO);
        }
    }

    // ==================== 配置预处理验证 ====================

    @Nested
    @DisplayName("关键词预处理")
    class Normalization {

        @Test
        @DisplayName("配置中含 null/空字符串/重复词 → init 时被过滤")
        void shouldNormalizeKeywordsAtInit() {
            IntentKeywordsConfig dirtyConfig = new IntentKeywordsConfig();
            Map<String, List<String>> dirty = new HashMap<>();
            // 故意加入空字符串、null、重复词、首尾带空格的关键词
            dirty.put(Intent.CHITCHAT.getCode(),
                    java.util.Arrays.asList("你好", "  你好  ", "", null, "你好", "Hi"));
            dirty.put(Intent.HOW_TO.getCode(), List.of("怎么"));
            dirtyConfig.setKeywords(dirty);

            KeywordIntentClassifier dirtyClassifier = new KeywordIntentClassifier(dirtyConfig);
            dirtyClassifier.init(); // 不应抛异常

            // 验证大小写不敏感的英文关键词也能正常工作
            IntentClassificationResult result = dirtyClassifier.classify("hi there");
            assertThat(result).isNotNull();
            assertThat(result.getIntent()).isEqualTo(Intent.CHITCHAT);
        }
    }
}