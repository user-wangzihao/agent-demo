package com.wzh.service.intent.impl;

import com.wzh.enums.Intent;
import com.wzh.service.MilvusService.SearchResult;
import com.wzh.service.intent.IntentBoostUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntentBoostUtil} 单元测试.
 *
 * <p>纯函数, 无外部依赖, 直接构造 SearchResult 验证 boost 行为.</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
@DisplayName("意图 boost 工具类测试")
class IntentBoostUtilTest {

    /** 构造一个 SearchResult, 只填 boost 关心的字段 */
    private SearchResult sr(String chunkType, float score) {
        SearchResult r = new SearchResult();
        r.chunkType = chunkType;
        r.score = score;
        return r;
    }

    // ==================== 正向: boost 生效 ====================

    @Nested
    @DisplayName("Boost 生效场景")
    class BoostApplied {

        @Test
        @DisplayName("HOW_TO 意图 → operation_guide 类型加权 1.15 倍")
        void shouldBoostOperationGuideForHowTo() {
            List<SearchResult> results = new ArrayList<>(Arrays.asList(
                    sr("feature_intro", 0.8f),
                    sr("operation_guide", 0.6f),
                    sr("error_solution", 0.7f)
            ));

            IntentBoostUtil.applyBoost(results, Intent.HOW_TO);

            // operation_guide: 0.6 * 1.15 = 0.69
            // 重排后顺序: feature_intro(0.8) → error_solution(0.7) → operation_guide(0.69)
            assertThat(results.get(0).chunkType).isEqualTo("feature_intro");
            assertThat(results.get(1).chunkType).isEqualTo("error_solution");
            assertThat(results.get(2).chunkType).isEqualTo("operation_guide");
            assertThat(results.get(2).score).isCloseTo(0.69f, org.assertj.core.data.Offset.offset(0.001f));
        }

        @Test
        @DisplayName("Boost 后让原本第二的 chunk 排到第一")
        void shouldReorderByBoostedScore() {
            List<SearchResult> results = new ArrayList<>(Arrays.asList(
                    sr("feature_intro", 0.85f),       // 不会 boost
                    sr("operation_guide", 0.78f)      // 0.78 * 1.15 = 0.897 → 反超
            ));

            IntentBoostUtil.applyBoost(results, Intent.HOW_TO);

            assertThat(results.get(0).chunkType).isEqualTo("operation_guide");
            assertThat(results.get(0).score).isCloseTo(0.897f, org.assertj.core.data.Offset.offset(0.001f));
        }

        @Test
        @DisplayName("TROUBLESHOOT 意图 → error_solution 加权")
        void shouldBoostErrorSolutionForTroubleshoot() {
            List<SearchResult> results = new ArrayList<>(Arrays.asList(
                    sr("error_solution", 0.5f),
                    sr("operation_guide", 0.6f)
            ));

            IntentBoostUtil.applyBoost(results, Intent.TROUBLESHOOT);

            // error_solution: 0.5 * 1.15 = 0.575 vs operation_guide 0.6
            // operation_guide 仍然第一
            assertThat(results.get(0).chunkType).isEqualTo("operation_guide");
            assertThat(results.get(1).chunkType).isEqualTo("error_solution");
            assertThat(results.get(1).score).isCloseTo(0.575f, org.assertj.core.data.Offset.offset(0.001f));
        }

        @Test
        @DisplayName("FEATURE_INTRO 意图 → feature_intro 加权")
        void shouldBoostFeatureIntro() {
            List<SearchResult> results = new ArrayList<>(Collections.singletonList(
                    sr("feature_intro", 0.7f)
            ));

            IntentBoostUtil.applyBoost(results, Intent.FEATURE_INTRO);

            assertThat(results.get(0).score).isCloseTo(0.805f, org.assertj.core.data.Offset.offset(0.001f));
        }
    }

    // ==================== Boost 不生效场景 ====================

    @Nested
    @DisplayName("Boost 不生效场景")
    class BoostSkipped {

        @Test
        @DisplayName("CHITCHAT 意图 → 不做任何处理")
        void shouldSkipForChitchat() {
            List<SearchResult> results = new ArrayList<>(Arrays.asList(
                    sr("operation_guide", 0.6f),
                    sr("error_solution", 0.7f)
            ));
            // 记录原始顺序和 score
            float[] originalScores = {0.6f, 0.7f};

            IntentBoostUtil.applyBoost(results, Intent.CHITCHAT);

            assertThat(results.get(0).score).isEqualTo(originalScores[0]);
            assertThat(results.get(1).score).isEqualTo(originalScores[1]);
        }

        @Test
        @DisplayName("DEFAULT 意图 → 不做任何处理")
        void shouldSkipForDefault() {
            List<SearchResult> results = new ArrayList<>(Collections.singletonList(
                    sr("operation_guide", 0.5f)
            ));

            IntentBoostUtil.applyBoost(results, Intent.DEFAULT);

            assertThat(results.get(0).score).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("intent 为 null → 不做任何处理")
        void shouldSkipForNullIntent() {
            List<SearchResult> results = new ArrayList<>(Collections.singletonList(
                    sr("operation_guide", 0.5f)
            ));

            IntentBoostUtil.applyBoost(results, null);

            assertThat(results.get(0).score).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("匹配 chunk_type 不存在 → 仅排序, 不改 score")
        void shouldNotChangeScoreWhenNoMatch() {
            // HOW_TO 意图, 但结果里没有 operation_guide
            List<SearchResult> results = new ArrayList<>(Arrays.asList(
                    sr("feature_intro", 0.8f),
                    sr("error_solution", 0.7f)
            ));

            IntentBoostUtil.applyBoost(results, Intent.HOW_TO);

            assertThat(results.get(0).score).isEqualTo(0.8f);
            assertThat(results.get(1).score).isEqualTo(0.7f);
        }
    }

    // ==================== 边界 / 健壮性 ====================

    @Nested
    @DisplayName("边界与健壮性")
    class Robustness {

        @Test
        @DisplayName("score = 0.95 → boost 后封顶 1.0, 不超出 cosine 范围")
        void shouldCapScoreAtCeiling() {
            List<SearchResult> results = new ArrayList<>(Collections.singletonList(
                    sr("operation_guide", 0.95f) // 0.95 * 1.15 = 1.0925, 应封顶到 1.0
            ));

            IntentBoostUtil.applyBoost(results, Intent.HOW_TO);

            assertThat(results.get(0).score).isEqualTo(IntentBoostUtil.SCORE_CEILING);
        }

        @Test
        @DisplayName("空 list → 不抛异常, 不报错")
        void shouldHandleEmptyList() {
            List<SearchResult> results = new ArrayList<>();
            IntentBoostUtil.applyBoost(results, Intent.HOW_TO);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("null list → 不抛异常 (兜底)")
        void shouldHandleNullList() {
            // 调用方按契约不应传 null, 但兜底不抛
            IntentBoostUtil.applyBoost(null, Intent.HOW_TO);
            // 没有异常即通过
        }
    }
}