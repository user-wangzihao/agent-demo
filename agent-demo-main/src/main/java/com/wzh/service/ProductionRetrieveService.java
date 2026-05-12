package com.wzh.service;

import com.wzh.config.ProductionRetrieveProperties;
import com.wzh.service.MilvusService.SearchResult;
import com.wzh.service.RerankService.RerankCandidate;
import com.wzh.service.RerankService.RerankHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产侧 RAG 检索流水线编排.
 *
 * <p><b>整体流程</b>:
 * <pre>
 * 用户 query (+前端可选 feature)
 *   ├─ Step A: 确定 feature
 *   │    ├─ 前端传了 → 三层匹配 → 命中 / 未命中
 *   │    └─ 没传     → LLM 提取 → 三层匹配 → 命中 / 未命中
 *   ├─ Step B: 检索
 *   │    ├─ feature 命中 → feature_aware (Milvus 标量过滤)
 *   │    └─ feature 未命中 (兜底链路) → rewriting → 多路并行 → RRF → reranker
 *   └─ Step C: 任何环节异常 → 降级 baseline
 * </pre></p>
 *
 * <p><b>评估集结论支撑</b>:
 * <ul>
 *   <li>feature_aware MRR@5=0.9042 NDCG@5=0.7044 (最优, 延迟最低)</li>
 *   <li>baseline    MRR@5=0.8431 NDCG@5=0.6783 (最终兜底)</li>
 *   <li>reranker / rewriting 单独无效, 仅在 feature 未命中时作为兜底链路使用</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionRetrieveService {

    private final DashScopeService dashScopeService;
    private final MilvusService milvusService;
    private final QueryRewriteService queryRewriteService;
    private final RerankService rerankService;
    private final FeatureNameMatcher featureNameMatcher;
    private final FeatureExtractService featureExtractService;
    private final ProductionRetrieveProperties props;

    /** RRF smoothing factor (业界标准 60) */
    private static final int RRF_K = 60;
    /** 兜底链路: 每路 query 的 Milvus 召回数 */
    private static final int FALLBACK_RECALL_PER_ROUTE = 15;

    // =========================================================================
    // 主入口
    // =========================================================================

    // 【改动 2】retrieve 方法重构: 调用 resolveFeature + retrieveByMatchedFeature
//   原方法体重构成下面这样, 对外行为完全等价

    /**
     * 生产侧检索入口 (兼容 AgentService 老链路).
     *
     * <p><b>等价改造</b>: 老逻辑 (resolve + 三层检索) 被拆成 resolveFeature() +
     * retrieveByMatchedFeature() 两步, 这里组合调用. 对外行为完全等价,
     * AgentService 调用方无需改动.</p>
     *
     * @param query                用户 query (通常是 enhancedMessage, 含图片描述)
     * @param selectedFeatureName  前端用户主动选择的 feature_name; null/空 = 用户未选
     * @return SearchResult 列表 (待 postProcessSearchResults 后处理)
     */
    public List<SearchResult> retrieve(String query, String selectedFeatureName) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String matchedFeature = resolveFeature(query, selectedFeatureName);
        return retrieveByMatchedFeature(query, matchedFeature);
    }

    // 【改动 1】resolveFeature 方法签名: private → public
//   原: private String resolveFeature(String query, String selectedFeatureName) { ... }
//   改为:

    /**
     * 解析 query 涉及的 feature_name (供 Graph 模式调用).
     *
     * <p><b>三层匹配逻辑</b>:
     * <ol>
     *   <li>前端传了 selectedFeatureName → 三层匹配 (精确 / 别名 / 模糊)</li>
     *   <li>前端未传 → LLM 提取候选 → 三层匹配</li>
     *   <li>都没命中 → 返回 null</li>
     * </ol></p>
     *
     * <p><b>暴露给外部的原因</b>: Graph 模式下 FeatureResolveNode 需要单独执行
     * 这一步, 然后把结果存到 OverAllState. 检索阶段从 state 取 matchedFeature,
     * 调 retrieveByMatchedFeature() 跳过解析直接检索, 避免重复执行.</p>
     *
     * @param query                用户 query
     * @param selectedFeatureName  前端用户主动选择的 feature_name; null/空 = 用户未选
     * @return 命中的 feature_name; 未命中 = null
     */
    public String resolveFeature(String query, String selectedFeatureName) {
        // 情况 1: 前端传了
        if (selectedFeatureName != null && !selectedFeatureName.trim().isEmpty()) {
            String matched = featureNameMatcher.match(selectedFeatureName);
            if (matched != null) {
                log.info("[PROD-RETRIEVE] 前端传入 feature 命中 {} → {}",
                        selectedFeatureName, matched);
                return matched;
            }
            log.warn("[PROD-RETRIEVE] 前端传入 feature 未命中 {}, 尝试 LLM 提取",
                    selectedFeatureName);
        }
        // 情况 2: 没传 (或前端值未匹配上) → LLM 提取
        if (!props.isFeatureExtractEnabled()) {
            return null;
        }
        String candidate = featureExtractService.extract(query);
        if (candidate == null) return null;
        return featureNameMatcher.match(candidate);
    }

    // 【改动 3】新增 retrieveByMatchedFeature 方法

    /**
     * 根据已解析的 matchedFeature 执行检索 (供 Graph 模式调用).
     *
     * <p><b>使用场景</b>: Graph 模式下 FeatureResolveNode 已经解析过 feature,
     * DocRetrieveNode 直接传入 matchedFeature, 跳过 resolveFeature 重复执行.</p>
     *
     * <p><b>检索流水线</b>:
     * <pre>
     *   matchedFeature 非空 + feature_aware 启用 → feature_aware 检索 (Milvus 标量过滤)
     *      └─ 召回为空 → fallback (rewriting + RRF + reranker)
     *   matchedFeature 为空                       → fallback
     *   异常                                      → 降级 baseline
     * </pre></p>
     *
     * @param query           用户 query
     * @param matchedFeature  已解析的 feature_name; null = 未命中, 跳过 feature_aware 直接 fallback
     * @return SearchResult 列表 (待 postProcessSearchResults 后处理)
     */
    public List<SearchResult> retrieveByMatchedFeature(String query, String matchedFeature) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            if (matchedFeature != null && props.isFeatureAwareEnabled()) {
                List<SearchResult> r = retrieveFeatureAware(query, matchedFeature);
                if (!r.isEmpty()) {
                    return r;
                }
                log.warn("[PROD-RETRIEVE] feature_aware 命中 feature={} 但召回为空, 走 fallback",
                        matchedFeature);
            }
            if (props.isRewritingFallbackEnabled()) {
                List<SearchResult> r = retrieveFallback(query);
                if (!r.isEmpty()) return r;
                log.warn("[PROD-RETRIEVE] fallback 链路结果为空, 走 baseline");
            }
            return retrieveBaseline(query);
        } catch (Exception e) {
            log.error("[PROD-RETRIEVE] 检索异常, 降级 baseline query={}", truncate(query, 40), e);
            return retrieveBaseline(query);
        }
    }

    // =========================================================================
    // Step B-1: feature_aware
    // =========================================================================

    private List<SearchResult> retrieveFeatureAware(String query, String featureName) {
        List<Float> vector = dashScopeService.getEmbedding(query);
        if (vector == null || vector.isEmpty()) {
            log.warn("[PROD-RETRIEVE][feature_aware] embedding 为空");
            return Collections.emptyList();
        }
        List<SearchResult> results = milvusService.searchByFeatures(
                vector,
                Collections.singletonList(featureName),
                props.getFeatureAwareTopK());
        log.info("[PROD-RETRIEVE][feature_aware] feature={} retrieved={} query={}",
                featureName, results.size(), truncate(query, 40));
        return results;
    }

    // =========================================================================
    // Step B-2: rewriting + 多路 + RRF + reranker (兜底)
    // =========================================================================

    private List<SearchResult> retrieveFallback(String query) {
        // 1) 改写 (失败仅用原 query)
        List<String> queries = new ArrayList<>();
        queries.add(query);
        try {
            queries.addAll(queryRewriteService.rewrite(query));
        } catch (Exception e) {
            log.warn("[PROD-RETRIEVE][fallback] 改写失败, 仅用原 query 单路 query={} err={}",
                    truncate(query, 40), e.getMessage());
        }

        // 2) 多路串行检索 (改写后总共 2-3 路, 串行延迟可接受;
        //    后续如果 numRewrites 调大可以改并行池)
        Map<String, Double> rrfScore = new HashMap<>();
        Map<String, SearchResult> firstOccurrence = new LinkedHashMap<>();
        for (String q : queries) {
            try {
                List<Float> v = dashScopeService.getEmbedding(q);
                if (v == null || v.isEmpty()) continue;
                List<SearchResult> route = milvusService.search(v, FALLBACK_RECALL_PER_ROUTE);
                for (int rank = 0; rank < route.size(); rank++) {
                    SearchResult sr = route.get(rank);
                    if (sr.chunkId == null || sr.chunkId.isEmpty() || "null".equals(sr.chunkId)) {
                        continue;
                    }
                    rrfScore.merge(sr.chunkId, 1.0 / (RRF_K + (rank + 1)), Double::sum);
                    firstOccurrence.putIfAbsent(sr.chunkId, sr);
                }
            } catch (Exception e) {
                log.warn("[PROD-RETRIEVE][fallback] 单路检索失败 q={} err={}",
                        truncate(q, 40), e.getMessage());
            }
        }

        // 3) RRF 融合, 取 reranker 候选池大小条数
        int candidatePoolSize = props.isRerankerEnabled()
                ? rerankService.getProperties().getRecallTopK()
                : props.getFinalTopK();
        List<SearchResult> candidates = rrfScore.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(candidatePoolSize)
                .map(e -> firstOccurrence.get(e.getKey()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 4) reranker (失败用 RRF 前 N 条降级)
        if (!props.isRerankerEnabled()) {
            return candidates.stream().limit(props.getFinalTopK()).collect(Collectors.toList());
        }
        List<RerankCandidate> rcands = candidates.stream()
                .filter(sr -> sr.chunkId != null && sr.content != null)
                .map(sr -> new RerankCandidate(sr.chunkId, sr.content))
                .collect(Collectors.toList());
        List<RerankHit> hits = rerankService.rerank(query, rcands, props.getFinalTopK());
        if (hits == null) {
            log.warn("[PROD-RETRIEVE][fallback] reranker 失败, 用 RRF 前 {} 条",
                    props.getFinalTopK());
            return candidates.stream().limit(props.getFinalTopK()).collect(Collectors.toList());
        }

        // 按 reranker 顺序还原 SearchResult (保留 score / featureName 等元数据)
        Map<String, SearchResult> byId = candidates.stream()
                .collect(Collectors.toMap(sr -> sr.chunkId, sr -> sr, (a, b) -> a));
        List<SearchResult> reranked = hits.stream()
                .map(h -> byId.get(h.chunkId))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        log.info("[PROD-RETRIEVE][fallback] 完成 routes={} candidatesAfterRRF={} reranked={}",
                queries.size(), candidates.size(), reranked.size());
        return reranked;
    }

    // =========================================================================
    // Step C: 终极兜底 baseline (等同改造前: 单路向量检索)
    // =========================================================================

    private List<SearchResult> retrieveBaseline(String query) {
        try {
            List<Float> v = dashScopeService.getEmbedding(query);
            if (v == null || v.isEmpty()) return Collections.emptyList();
            return milvusService.search(v, props.getFinalTopK());
        } catch (Exception e) {
            log.error("[PROD-RETRIEVE][baseline] 兜底也失败 query={}", truncate(query, 40), e);
            return Collections.emptyList();
        }
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}