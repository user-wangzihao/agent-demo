package com.wzh.agentdemo.evaltools.metrics;

import java.util.List;
import java.util.Set;

/**
 * 检索质量指标纯函数工具.
 *
 * <p><b>语义约定 (Batch 5-B 决策)</b>: 评估集采用"宽松集合标注" ——
 * 一个 case 的 expectedChunks 是被标注为"相关"的 chunk 池 (规模 7~36).
 * 所有指标按 <i>top-K 命中 expectedChunks 集合</i> 的方式计算, 而非
 * 对集合内每个 chunk 单独求 rank 再平均.</p>
 *
 * <p><b>三个指标的语义</b>:
 * <ul>
 *   <li>MRR@K — top-K 内"首次命中相关 chunk"的 rank 倒数; 未命中 = 0.
 *       回答"系统能否在前 K 给出至少一个相关结果, 且多靠前".</li>
 *   <li>NDCG@K — 二值相关性 (在 expected 集合内 = 1, 否则 = 0) 的折扣累计增益,
 *       归一化到 IDCG (前 min(K, |expected|) 个位置全命中的理想值).
 *       回答"top-K 的相关性排序与理想排序的接近程度".</li>
 *   <li>Recall@K — |top-K ∩ expected| / |expected|.
 *       注: expected 大时上限受 K/|expected| 限制, 这是诚实的基线刻画.</li>
 * </ul>
 *
 * <p>所有方法均为静态纯函数, 输入空集合或 K&lt;=0 时返回 0.0 而非抛异常,
 * 由调用方决定是否计入聚合.</p>
 *
 * @author wzh
 * @since 2026-05-20 (评估 CI Batch 5-B)
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    /**
     * MRR@K (Mean Reciprocal Rank, single case).
     * <p>top-K 中第一个出现在 expected 集合的 rank 倒数; 全 miss 返回 0.</p>
     *
     * @param retrievedIds 系统召回的 chunk_id 列表 (按 rank 升序, 1-based 通过下标隐含)
     * @param expectedIds  ground truth 集合
     * @param k            截断深度
     */
    public static double reciprocalRankAtK(List<String> retrievedIds, Set<String> expectedIds, int k) {
        if (retrievedIds == null || expectedIds == null || expectedIds.isEmpty() || k <= 0) {
            return 0.0;
        }
        int bound = Math.min(retrievedIds.size(), k);
        for (int i = 0; i < bound; i++) {
            if (expectedIds.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);  // rank 1-based
            }
        }
        return 0.0;
    }

    /**
     * NDCG@K (binary relevance), single case.
     * <p>DCG = Σ rel_i / log2(i+1); IDCG = 前 min(K, |expected|) 个位置全命中的理想 DCG.</p>
     */
    public static double ndcgAtK(List<String> retrievedIds, Set<String> expectedIds, int k) {
        if (retrievedIds == null || expectedIds == null || expectedIds.isEmpty() || k <= 0) {
            return 0.0;
        }
        double dcg = 0.0;
        int bound = Math.min(retrievedIds.size(), k);
        for (int i = 0; i < bound; i++) {
            if (expectedIds.contains(retrievedIds.get(i))) {
                // 二值相关性: rel=1, gain = 1 / log2(rank+1), rank 是 1-based
                dcg += 1.0 / log2(i + 2);
            }
        }

        // IDCG: 前 min(K, |expected|) 个位置全命中
        int idealHits = Math.min(k, expectedIds.size());
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /**
     * Recall@K, single case.
     * <p>|top-K ∩ expected| / |expected|.</p>
     */
    public static double recallAtK(List<String> retrievedIds, Set<String> expectedIds, int k) {
        if (retrievedIds == null || expectedIds == null || expectedIds.isEmpty() || k <= 0) {
            return 0.0;
        }
        int bound = Math.min(retrievedIds.size(), k);
        int hits = 0;
        for (int i = 0; i < bound; i++) {
            if (expectedIds.contains(retrievedIds.get(i))) {
                hits++;
            }
        }
        return (double) hits / expectedIds.size();
    }

    /**
     * top-K 是否至少命中一个 expected (用于统计 hit_rate / fail case).
     */
    public static boolean hitAtK(List<String> retrievedIds, Set<String> expectedIds, int k) {
        if (retrievedIds == null || expectedIds == null || expectedIds.isEmpty() || k <= 0) {
            return false;
        }
        int bound = Math.min(retrievedIds.size(), k);
        for (int i = 0; i < bound; i++) {
            if (expectedIds.contains(retrievedIds.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * top-K 中命中 expected 的数量 (用于报告里展示 "hit X / expected Y").
     */
    public static int hitCountAtK(List<String> retrievedIds, Set<String> expectedIds, int k) {
        if (retrievedIds == null || expectedIds == null || expectedIds.isEmpty() || k <= 0) {
            return 0;
        }
        int bound = Math.min(retrievedIds.size(), k);
        int hits = 0;
        for (int i = 0; i < bound; i++) {
            if (expectedIds.contains(retrievedIds.get(i))) {
                hits++;
            }
        }
        return hits;
    }

    private static double log2(int x) {
        return Math.log(x) / Math.log(2);
    }
}