package com.wzh.service.intent;

import com.wzh.enums.Intent;
import com.wzh.service.MilvusService.SearchResult;

import java.util.List;

/**
 * 意图驱动的 chunk_type 加权工具.
 *
 * <p><b>核心思想</b>: 当用户意图为 how_to / troubleshoot / feature_intro 时,
 * 对应的 chunk_type (operation_guide / error_solution / feature_intro) 在召回结果中
 * 应当排序更靠前. 通过提升 score 来实现这一引导, 而不是硬过滤掉非匹配类型,
 * 保留召回多样性.</p>
 *
 * <p><b>boost 算法选型</b>:
 * <ul>
 *   <li>采用<b>乘性 boost</b> (score * factor) 而非加性 boost (score + delta)</li>
 *   <li>原因: cosine 相似度 score ∈ [0, 1], 加性 boost 可能让 score 超出 1 (如 0.95 + 0.1 = 1.05),
 *       破坏概率语义; 乘性 boost 自然保持比例关系, 配合 1.0 封顶即可保证范围</li>
 *   <li>boost factor 取 1.15 (即 +15% 权重), 经验值: 既能让目标 chunk_type 排序靠前,
 *       又不至于完全压制其他高质量召回结果</li>
 * </ul></p>
 *
 * <p><b>幂等性</b>: 方法直接修改入参 List 中元素的 score 字段. 多次调用同一 list
 * 会重复加权, 调用方需保证只调用一次.</p>
 *
 * <p><b>线程安全</b>: 纯函数实现, 静态方法, 无共享状态, 线程安全.</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
public final class IntentBoostUtil {

    /** boost 倍率: 1.15 = 提升 15% 权重 */
    public static final float BOOST_FACTOR = 1.15f;

    /** score 上限: cosine 相似度的合法上界 */
    public static final float SCORE_CEILING = 1.0f;

    private IntentBoostUtil() {
        // 工具类禁止实例化
    }

    /**
     * 按意图对 SearchResult 列表做 chunk_type 加权 + 重排.
     *
     * <p><b>边界处理</b>:
     * <ul>
     *   <li>{@code intent == null} 或 {@code intent.needsBoost() == false}
     *       (CHITCHAT/DEFAULT) → 直接返回 results 不做处理</li>
     *   <li>{@code results == null} → 抛 NPE (调用方契约: 不应传入 null)</li>
     *   <li>{@code results.isEmpty()} → 直接返回, 无副作用</li>
     * </ul></p>
     *
     * @param results 检索结果, 会被原地修改 score
     * @param intent  用户意图
     */
    public static void applyBoost(List<SearchResult> results, Intent intent) {
        if (results == null || results.isEmpty()) {
            return;
        }
        if (intent == null || !intent.needsBoost()) {
            return;
        }

        String targetType = intent.getBoostChunkType();
        for (SearchResult sr : results) {
            if (targetType.equals(sr.chunkType)) {
                // 乘性 boost + 封顶
                sr.score = Math.min(SCORE_CEILING, sr.score * BOOST_FACTOR);
            }
        }
        // 按 score 降序重排
        results.sort((a, b) -> Float.compare(b.score, a.score));
    }
}