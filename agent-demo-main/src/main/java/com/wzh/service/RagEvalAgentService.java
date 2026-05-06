package com.wzh.service;

import com.wzh.config.RewriteProperties;
import com.wzh.service.MilvusService.SearchResult;
import com.wzh.service.RerankService.RerankCandidate;
import com.wzh.service.RerankService.RerankHit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * RAG 评估专用检索服务.
 *
 * <p><b>设计原则</b>: 完全独立于 {@link AgentService},不影响线上对话主流程.</p>
 *
 * <p><b>已支持流水线</b>:
 * <ul>
 *   <li>{@link #PIPELINE_BASELINE}: 纯向量检索 (阶段 0)</li>
 *   <li>{@link #PIPELINE_RERANKER}: 向量粗召回 → DashScope gte-rerank-v2 精排 (阶段 1)</li>
 *   <li>{@link #PIPELINE_REWRITING}: Query Rewriting → 多路并行检索 → RRF 融合 (阶段 2)</li>
 *   <li>{@link #PIPELINE_FULL}: Rewriting + 多路检索 + RRF + Reranker (阶段 2 全流程)</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-04-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvalAgentService {

    private final DashScopeService dashScopeService;
    private final MilvusService milvusService;
    private final RerankService rerankService;
    private final QueryRewriteService queryRewriteService;
    private final RewriteProperties rewriteProperties;

    /** 流水线类型: 纯向量检索 (baseline) */
    public static final String PIPELINE_BASELINE = "baseline";
    /** 流水线类型: 向量粗召回 + Reranker */
    public static final String PIPELINE_RERANKER = "reranker";
    /** 流水线类型: Query Rewriting + 多路并行检索 + RRF */
    public static final String PIPELINE_REWRITING = "rewriting";
    /** 流水线类型: Rewriting + 多路 + RRF + Reranker (全流程) */
    public static final String PIPELINE_FULL = "rewriting+reranker";
    /** 流水线类型: 用户提供 feature_name -> Milvus 元数据过滤检索 */
    public static final String PIPELINE_FEATURE_AWARE = "feature_aware";

    /** 多路并行检索专用线程池 (按 RewriteProperties.threadPoolSize 创建) */
    private ExecutorService retrieveExecutor;

    @PostConstruct
    public void init() {
        int poolSize = Math.max(1, rewriteProperties.getThreadPoolSize());
        // 命名线程池便于 jstack/日志排查
        this.retrieveExecutor = Executors.newFixedThreadPool(poolSize, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rag-eval-retrieve-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        log.info("[RAG-EVAL] 检索线程池初始化完成 size={}", poolSize);
    }

    @PreDestroy
    public void destroy() {
        if (retrieveExecutor != null) {
            retrieveExecutor.shutdown();
            try {
                if (!retrieveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    retrieveExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                retrieveExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[RAG-EVAL] 检索线程池已关闭");
        }
    }

    // =========================================================================
// 流水线分发入口
// =========================================================================

    /**
     * 流水线分发入口 (向后兼容版本).
     *
     * <p>不需要 feature 过滤的 pipeline 调这个,等同于 features=null.</p>
     */
    public List<String> retrieve(String query, String pipeline, int topK) {
        return retrieve(query, pipeline, topK, null);
    }

    /**
     * 流水线分发入口 (含 feature 过滤).
     *
     * @param query    用户原始 query
     * @param pipeline 流水线类型
     * @param topK     返回的 top-K
     * @param features 用户提供的 feature_name 列表;
     *                 仅 PIPELINE_FEATURE_AWARE 使用,其他 pipeline 忽略
     * @return 检索到的 chunk_id 列表
     */
    public List<String> retrieve(String query, String pipeline, int topK, List<String> features) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        switch (pipeline) {
            case PIPELINE_BASELINE:
                return retrieveBaseline(query, topK);
            case PIPELINE_RERANKER:
                return retrieveWithReranker(query, topK);
            case PIPELINE_REWRITING:
                return retrieveWithRewriting(query, topK);
            case PIPELINE_FULL:
                return retrieveFull(query, topK);
            case PIPELINE_FEATURE_AWARE:
                return retrieveWithFeatureAware(query, topK, features);
            default:
                throw new IllegalArgumentException(
                        "未知 pipeline: " + pipeline
                                + " (当前支持: " + PIPELINE_BASELINE + ", " + PIPELINE_RERANKER
                                + ", " + PIPELINE_REWRITING + ", " + PIPELINE_FULL
                                + ", " + PIPELINE_FEATURE_AWARE + ")");
        }
    }

    // =========================================================================
    // Baseline: 纯向量检索
    // =========================================================================

    private List<String> retrieveBaseline(String query, int topK) {
        try {
            List<SearchResult> rawResults = embedAndSearch(query, topK);
            return rawResults.stream()
                    .map(sr -> sr.chunkId)
                    .filter(id -> id != null && !id.isEmpty() && !"null".equals(id))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[RAG-EVAL] baseline 检索异常 query={}", query, e);
            return new ArrayList<>();
        }
    }

    // =========================================================================
    // Reranker: 向量粗召回 + DashScope gte-rerank-v2 精排
    // =========================================================================

    private List<String> retrieveWithReranker(String query, int topK) {
        if (!rerankService.getProperties().isEnabled()) {
            log.warn("[RAG-EVAL] reranker 未启用,退回 baseline");
            return retrieveBaseline(query, topK);
        }

        int recallTopK = rerankService.getProperties().getRecallTopK();
        int rerankTopN = Math.max(topK, rerankService.getProperties().getRerankTopN());

        List<SearchResult> rawResults;
        try {
            rawResults = embedAndSearch(query, recallTopK);
            if (rawResults.isEmpty()) {
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("[RAG-EVAL] reranker-粗召回阶段异常 query={}", query, e);
            return new ArrayList<>();
        }

        List<RerankHit> hits = callReranker(query, rawResults, rerankTopN);
        if (hits == null) {
            // reranker 失败: 降级到 baseline 前 N 条
            if (rerankService.getProperties().isFallbackOnError()) {
                log.warn("[RAG-EVAL] reranker 失败,降级到 baseline 前 {} 条 query={}", rerankTopN, query);
                return rawResults.stream()
                        .limit(rerankTopN)
                        .map(sr -> sr.chunkId)
                        .collect(Collectors.toList());
            }
            return new ArrayList<>();
        }

        return hits.stream().map(h -> h.chunkId).collect(Collectors.toList());
    }

    // =========================================================================
    // Rewriting: Query Rewriting + 多路并行检索 + RRF (阶段 2)
    // =========================================================================

    /**
     * Rewriting 流水线:
     * <ol>
     *   <li>调 LLM 生成 N 条改写</li>
     *   <li>原 query + N 条改写 = M 条 query 并行做向量检索 (各取 recallTopK)</li>
     *   <li>用 RRF 融合 M 路结果</li>
     *   <li>取融合后 top-K 返回</li>
     * </ol>
     *
     * <p><b>失败降级</b>:
     * <ul>
     *   <li>改写失败 → 降级为单 query baseline (取 topK)</li>
     *   <li>个别检索路失败 → 该路结果为空,其他路正常 (RRF 容忍部分缺失)</li>
     *   <li>所有检索路失败 → 返回空</li>
     * </ul></p>
     */
    private List<String> retrieveWithRewriting(String query, int topK) {
        if (!rewriteProperties.isEnabled()) {
            log.warn("[RAG-EVAL] rewriting 未启用,退回 baseline");
            return retrieveBaseline(query, topK);
        }

        // Step 1: 生成改写 + 失败降级
        List<String> queries = generateAllQueries(query);
        if (queries.isEmpty()) {
            // 极端情况: 改写失败 + 配置不允许降级
            return new ArrayList<>();
        }

        // Step 2: 多路并行检索
        List<List<SearchResult>> allResults = parallelSearch(queries, rewriteProperties.getRecallTopK());

        // Step 3: RRF 融合 + 取 topK
        List<String> fused = mergeWithRRF(allResults, topK, rewriteProperties.getRrfK());
        log.info("[RAG-EVAL] rewriting 完成 query={} routes={} topK={} fused={}",
                truncate(query, 40), queries.size(), topK, fused.size());
        return fused;
    }

    // =========================================================================
    // Full: Rewriting + 多路 + RRF + Reranker (阶段 2 全流程)
    // =========================================================================

    /**
     * Full 流水线 (Rewriting + Reranker 全流程):
     * <ol>
     *   <li>同 rewriting: 改写 → 多路并行检索 → RRF 融合得到候选池 (取较多条数,给 reranker 留空间)</li>
     *   <li>把候选池送 reranker 精排</li>
     *   <li>取 reranker 输出 top-K</li>
     * </ol>
     *
     * <p><b>关键参数</b>: RRF 融合后取 {@code rerank.recallTopK} 条进 reranker
     * (即 reranker 的候选池大小,默认 20).</p>
     */
    private List<String> retrieveFull(String query, int topK) {
        if (!rewriteProperties.isEnabled()) {
            log.warn("[RAG-EVAL] rewriting 未启用,full 退化为 reranker");
            return retrieveWithReranker(query, topK);
        }
        if (!rerankService.getProperties().isEnabled()) {
            log.warn("[RAG-EVAL] reranker 未启用,full 退化为 rewriting");
            return retrieveWithRewriting(query, topK);
        }

        // Step 1: 改写 + 降级
        List<String> queries = generateAllQueries(query);
        if (queries.isEmpty()) {
            return new ArrayList<>();
        }

        // Step 2: 多路并行检索
        List<List<SearchResult>> allResults = parallelSearch(queries, rewriteProperties.getRecallTopK());

        // Step 3: RRF 融合,取 reranker 候选池大小条数 (不是 topK)
        int rerankerCandidatePoolSize = rerankService.getProperties().getRecallTopK();
        List<SearchResult> candidates = mergeWithRRFAsSearchResults(
                allResults, rerankerCandidatePoolSize, rewriteProperties.getRrfK());
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        // Step 4: Reranker 精排
        int rerankTopN = Math.max(topK, rerankService.getProperties().getRerankTopN());
        List<RerankHit> hits = callReranker(query, candidates, rerankTopN);
        if (hits == null) {
            // reranker 失败: 用 RRF 结果前 N 条降级
            if (rerankService.getProperties().isFallbackOnError()) {
                log.warn("[RAG-EVAL] full-reranker 失败,降级到 RRF 前 {} 条", rerankTopN);
                return candidates.stream()
                        .limit(rerankTopN)
                        .map(sr -> sr.chunkId)
                        .collect(Collectors.toList());
            }
            return new ArrayList<>();
        }

        log.info("[RAG-EVAL] full 完成 query={} routes={} candidatesAfterRRF={} reranked={}",
                truncate(query, 40), queries.size(), candidates.size(), hits.size());
        return hits.stream().map(h -> h.chunkId).collect(Collectors.toList());
    }

    // =========================================================================
// FeatureAware: 用户提供 feature_name -> Milvus 元数据过滤检索
// =========================================================================

    /**
     * Feature-Aware 流水线:
     * <ol>
     *   <li>从调用方接收用户提供的 feature_name (评估场景: 来自 rag_eval_set.sub_category)</li>
     *   <li>用 features 在 Milvus 做标量过滤 + 向量检索</li>
     *   <li>取 top-K 返回</li>
     * </ol>
     *
     * <p><b>核心理念</b>: feature 由用户显式提供 (生产场景: 前端下拉框;
     * 评估场景: 评估记录预先标注),不是 LLM 猜的 — 100% 准确,0 LLM 调用成本.</p>
     *
     * <p><b>降级策略</b>:
     * <ul>
     *   <li>features 为 null/空 → 退化 baseline (用户没指定功能,只能全库检索)</li>
     *   <li>过滤后无结果 → 退化 baseline (避免漏召回)</li>
     *   <li>Milvus 异常 → 退化 baseline</li>
     * </ul></p>
     *
     * @param query    用户 query
     * @param topK     返回数量
     * @param features 用户指定的功能点列表;null/空 = 不过滤
     */
    private List<String> retrieveWithFeatureAware(String query, int topK, List<String> features) {
        // 防御 1: features 为空 → 没法过滤,降级 baseline
        if (features == null || features.isEmpty()) {
            log.warn("[RAG-EVAL] feature_aware: 未提供 feature,降级 baseline query={}",
                    truncate(query, 40));
            return retrieveBaseline(query, topK);
        }

        try {
            List<Float> vector = dashScopeService.getEmbedding(query);
            if (vector == null || vector.isEmpty()) {
                log.warn("[RAG-EVAL] feature_aware: embedding 为空 query={}", truncate(query, 40));
                return Collections.emptyList();
            }

            // Milvus 按 feature 过滤
            List<MilvusService.SearchResult> results = milvusService.searchByFeatures(
                    vector, features, topK);

            // 防御 2: 过滤后无结果 → 降级 baseline (可能 feature 标错了或 Milvus 数据有问题)
            if (results.isEmpty()) {
                log.warn("[RAG-EVAL] feature_aware: 过滤后无结果,降级 baseline features={} query={}",
                        features, truncate(query, 40));
                return retrieveBaseline(query, topK);
            }

            log.info("[RAG-EVAL] feature_aware 完成 features={} retrieved={} query={}",
                    features, results.size(), truncate(query, 40));

            return results.stream()
                    .map(sr -> sr.chunkId)
                    .filter(id -> id != null && !id.isEmpty() && !"null".equals(id))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("[RAG-EVAL] feature_aware 异常 features={} query={}", features, query, e);
            // 防御 3: 任何异常 → 降级 baseline
            return retrieveBaseline(query, topK);
        }
    }

    // =========================================================================
    // 公共工具: 改写 + 降级
    // =========================================================================

    /**
     * 生成"原 query + 改写"的完整查询列表.
     *
     * @return 长度 = numRewrites + 1 (含原 query);改写失败时按降级策略返回 [query] 或 []
     */
    private List<String> generateAllQueries(String query) {
        List<String> queries = new ArrayList<>();
        queries.add(query);  // 原 query 永远在首位 (兜底,即使改写跑偏也保证基础召回)

        try {
            List<String> rewrites = queryRewriteService.rewrite(query);
            queries.addAll(rewrites);
            return queries;
        } catch (Exception e) {
            log.error("[RAG-EVAL] 改写失败 query={} err={}", truncate(query, 40), e.getMessage());
            if (rewriteProperties.isFallbackOnError()) {
                log.warn("[RAG-EVAL] 改写失败降级,只用原 query 单路检索");
                return queries;  // 只剩原 query,等同于 baseline
            }
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // 公共工具: 单路 embedding + 检索
    // =========================================================================

    /**
     * 同步执行: 一条 query 的 embedding + Milvus 检索.
     */
    private List<SearchResult> embedAndSearch(String query, int topK) {
        List<Float> vector = dashScopeService.getEmbedding(query);
        if (vector == null || vector.isEmpty()) {
            log.warn("[RAG-EVAL] embedding 为空 query={}", truncate(query, 40));
            return Collections.emptyList();
        }
        return milvusService.search(vector, topK);
    }

    // =========================================================================
    // 公共工具: 多路并行检索
    // =========================================================================

    /**
     * 多条 query 并行做 embedding + Milvus 检索.
     *
     * <p>个别路失败返回空 List,不影响其他路 (RRF 容忍部分缺失).</p>
     *
     * @param queries 要检索的 query 列表
     * @param topK    每路召回的 top-K
     * @return 与 queries 顺序一一对应的检索结果;失败的路对应空 List
     */
    private List<List<SearchResult>> parallelSearch(List<String> queries, int topK) {
        List<CompletableFuture<List<SearchResult>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return embedAndSearch(q, topK);
                    } catch (Exception e) {
                        log.warn("[RAG-EVAL] 单路检索失败 query={} err={}",
                                truncate(q, 40), e.getMessage());
                        return Collections.<SearchResult>emptyList();
                    }
                }, retrieveExecutor))
                .collect(Collectors.toList());

        // 等所有路完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 公共工具: Reranker 调用 (统一封装,Reranker / Full 两个 pipeline 共用)
    // =========================================================================

    /**
     * 把 SearchResult 列表喂给 reranker.
     *
     * @return reranker 的 top-N 结果;失败返回 null
     */
    private List<RerankHit> callReranker(String query, List<SearchResult> candidates, int topN) {
        List<RerankCandidate> rerankCandidates = candidates.stream()
                .filter(sr -> sr.chunkId != null && sr.content != null)
                .map(sr -> new RerankCandidate(sr.chunkId, sr.content))
                .collect(Collectors.toList());
        if (rerankCandidates.isEmpty()) {
            return Collections.emptyList();
        }
        return rerankService.rerank(query, rerankCandidates, topN);
    }

    // =========================================================================
    // 公共工具: RRF (Reciprocal Rank Fusion)
    // =========================================================================

    /**
     * RRF 融合算法 — 返回 chunkId 列表.
     *
     * <p>公式: {@code score(c) = Σ 1 / (k + rank_i)}<br>
     * 含义: 一个 chunk 在多路里都排名靠前 → 总分高;某一路缺席不致命.</p>
     *
     * <p><b>k 参数</b>: 来自 Cormack et al. 2009 论文的经典推荐值 60.</p>
     *
     * @param allResults 多路检索结果
     * @param topK       融合后取前几条
     * @param rrfK       RRF smoothing factor
     * @return chunkId 列表 (按融合分数降序)
     */
    private List<String> mergeWithRRF(List<List<SearchResult>> allResults, int topK, int rrfK) {
        List<SearchResult> fused = mergeWithRRFAsSearchResults(allResults, topK, rrfK);
        return fused.stream().map(sr -> sr.chunkId).collect(Collectors.toList());
    }

    /**
     * RRF 融合算法 — 返回 SearchResult 列表 (供 full 流水线送 reranker 用,需要 content 字段).
     *
     * <p><b>实现细节</b>:
     * <ul>
     *   <li>用 LinkedHashMap 维持插入顺序,稳定性更好</li>
     *   <li>遇到 chunkId 重复时,score 累加,SearchResult 取首次出现的 (任何一份都行,内容相同)</li>
     *   <li>过滤掉 chunkId 为 null/空/"null" 的脏数据</li>
     * </ul></p>
     */
    private List<SearchResult> mergeWithRRFAsSearchResults(List<List<SearchResult>> allResults,
                                                           int topK, int rrfK) {
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, SearchResult> firstOccurrence = new LinkedHashMap<>();

        for (List<SearchResult> oneRoute : allResults) {
            for (int rank = 0; rank < oneRoute.size(); rank++) {
                SearchResult sr = oneRoute.get(rank);
                if (sr.chunkId == null || sr.chunkId.isEmpty() || "null".equals(sr.chunkId)) {
                    continue;
                }
                // RRF 公式: 1 / (k + rank);rank 从 1 开始 (业界标准是 1-based)
                double contribution = 1.0 / (rrfK + (rank + 1));
                scoreMap.merge(sr.chunkId, contribution, Double::sum);
                firstOccurrence.putIfAbsent(sr.chunkId, sr);
            }
        }

        // 按分数降序,取 topK
        return scoreMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> firstOccurrence.get(e.getKey()))
                .collect(Collectors.toList());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}