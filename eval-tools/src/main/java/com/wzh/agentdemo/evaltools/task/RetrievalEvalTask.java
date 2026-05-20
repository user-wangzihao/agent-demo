package com.wzh.agentdemo.evaltools.task;

import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.llm.DashScopeClient;
import com.wzh.agentdemo.evaltools.metrics.RetrievalMetrics;
import com.wzh.agentdemo.evaltools.milvus.MilvusBulkReader;
import com.wzh.agentdemo.evaltools.model.ChunkCandidate;
import com.wzh.agentdemo.evaltools.model.EvalCase;
import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import com.wzh.agentdemo.evaltools.parser.EvalSetParser;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索质量评估任务 (MRR@5 / NDCG@5 / Recall@5).
 *
 * <p><b>Batch 5-B 真实现</b>: 复用 {@link DashScopeClient#embed(String)} 拿 query 向量,
 * 调用 {@link MilvusBulkReader#vectorSearch} 直连 Milvus 跑 top-K 检索, 用
 * {@link RetrievalMetrics} 计算指标. eval-tools 自闭环, 主应用零侵入.</p>
 *
 * <p><b>评估范围 (Batch 5-B 决策)</b>: 仅评估"裸向量检索"基线 ——
 * 不带 feature_name 过滤, 不带 rerank, 不带 RRF 融合. 评估的是 RAG 召回的底层
 * 向量质量, 后续 pipeline 端到端质量另起一刀. 这意味着评估数字会比线上感受低
 * (线上有 rerank 加成), 但作为基线是诚实的.</p>
 *
 * <p><b>语义</b> (详见 {@link RetrievalMetrics}):
 * <ul>
 *   <li>采用宽松集合标注: expected 是相关 chunk 池 (7~36 个), 命中任意一个即算召回</li>
 *   <li>K=5 是主指标, K=10 作为辅助指标一并输出</li>
 *   <li>fail case 定义: top5 与 expected 完全无交集 (top5_hit=0)</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-19 (Batch 1 骨架)
 * @since 2026-05-20 (Batch 5-B 真实现)
 */
@Slf4j
public class RetrievalEvalTask implements EvalTask {

    public static final String TASK_NAME = "retrieval";
    public static final String DISPLAY_NAME = "检索质量 (MRR@5 / NDCG@5 / Recall@5)";

    /** 主评估深度 */
    private static final int K_PRIMARY = 5;
    /** 辅助评估深度 (与既有 AuditConfig.VECTOR_TOP_K=10 对齐, 一次检索两个 K 都能算) */
    private static final int K_SECONDARY = 10;

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public EvalTaskResult run() {
        long t0 = System.currentTimeMillis();

        // ---------- 1. 加载评估集 ----------
        List<EvalCase> cases;
        try {
            String text = loadResource(AuditConfig.EVAL_SET_RESOURCE);
            cases = new EvalSetParser().parse(text);
        } catch (Exception e) {
            log.error("[{}] 加载评估集失败: {}", TASK_NAME, e.getMessage(), e);
            return EvalTaskResult.error(TASK_NAME, DISPLAY_NAME,
                    "加载 " + AuditConfig.EVAL_SET_RESOURCE + " 失败: " + e.getMessage());
        }
        if (cases.isEmpty()) {
            return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                    "评估集 " + AuditConfig.EVAL_SET_RESOURCE + " 解析后为空");
        }
        log.info("[{}] 加载 {} 个 case", TASK_NAME, cases.size());

        // ---------- 2. 准备客户端 (try-with-resources) ----------
        DashScopeClient ds = new DashScopeClient();

        // 聚合器
        List<Double> mrr5List = new ArrayList<>();
        List<Double> ndcg5List = new ArrayList<>();
        List<Double> recall5List = new ArrayList<>();
        List<Double> mrr10List = new ArrayList<>();
        List<Double> ndcg10List = new ArrayList<>();
        List<Double> recall10List = new ArrayList<>();
        int hit5Cases = 0;     // top5 至少命中一个 expected 的 case 数
        int passCount = 0;     // == hit5Cases, 显式分开命名以匹配 EvalTaskResult.passCount 语义
        int failCount = 0;     // top5 完全 miss
        int embedFailCases = 0;
        List<String> failureDetails = new ArrayList<>();
        int evaluatedTotal = 0;  // 排除 embedding 失败的 case 数, 用于聚合分母

        try (MilvusBulkReader milvus = new MilvusBulkReader()) {

            // ---------- 3. 逐 case 处理 ----------
            for (EvalCase ec : cases) {
                log.info("─── evalId={} [{} | {}] query={}",
                        ec.getEvalId(), ec.getCategory(), ec.getFeatureName(), ec.getQuery());

                // 3a. embedding
                List<Float> queryVec;
                try {
                    queryVec = ds.embed(ec.getQuery());
                } catch (Exception e) {
                    log.warn("evalId={} embedding 失败, 跳过此 case: {}",
                            ec.getEvalId(), e.getMessage());
                    embedFailCases++;
                    failureDetails.add(String.format(
                            "evalId=%d query=\"%s\" 原因=embedding失败(%s)",
                            ec.getEvalId(), ec.getQuery(), e.getMessage()));
                    continue;
                }

                // 3b. 向量 Top-10 检索 (一次拿 10, K=5 / K=10 都能算)
                List<ChunkCandidate> hits;
                try {
                    hits = milvus.vectorSearch(queryVec, K_SECONDARY);
                } catch (Exception e) {
                    log.warn("evalId={} Milvus 检索失败, 跳过此 case: {}",
                            ec.getEvalId(), e.getMessage());
                    embedFailCases++;
                    failureDetails.add(String.format(
                            "evalId=%d query=\"%s\" 原因=Milvus检索失败(%s)",
                            ec.getEvalId(), ec.getQuery(), e.getMessage()));
                    continue;
                }

                List<String> retrievedIds = hits.stream()
                        .map(ChunkCandidate::getChunkId)
                        .collect(Collectors.toList());
                Set<String> expected = new HashSet<>(ec.getExpectedChunks());

                // 3c. 算指标
                double mrr5   = RetrievalMetrics.reciprocalRankAtK(retrievedIds, expected, K_PRIMARY);
                double ndcg5  = RetrievalMetrics.ndcgAtK(retrievedIds, expected, K_PRIMARY);
                double recall5 = RetrievalMetrics.recallAtK(retrievedIds, expected, K_PRIMARY);
                double mrr10  = RetrievalMetrics.reciprocalRankAtK(retrievedIds, expected, K_SECONDARY);
                double ndcg10 = RetrievalMetrics.ndcgAtK(retrievedIds, expected, K_SECONDARY);
                double recall10 = RetrievalMetrics.recallAtK(retrievedIds, expected, K_SECONDARY);
                boolean hit5 = RetrievalMetrics.hitAtK(retrievedIds, expected, K_PRIMARY);
                int hitCnt5 = RetrievalMetrics.hitCountAtK(retrievedIds, expected, K_PRIMARY);

                mrr5List.add(mrr5);
                ndcg5List.add(ndcg5);
                recall5List.add(recall5);
                mrr10List.add(mrr10);
                ndcg10List.add(ndcg10);
                recall10List.add(recall10);
                evaluatedTotal++;

                if (hit5) {
                    hit5Cases++;
                    passCount++;
                } else {
                    failCount++;
                    failureDetails.add(String.format(
                            "evalId=%d query=\"%s\" expected_size=%d top5_hit=0 top5=[%s]",
                            ec.getEvalId(), ec.getQuery(), expected.size(),
                            retrievedIds.stream().limit(K_PRIMARY).collect(Collectors.joining(", "))));
                }

                log.info("evalId={} MRR@5={} NDCG@5={} Recall@5={} hit@5={}/{}",
                        ec.getEvalId(),
                        fmt(mrr5), fmt(ndcg5), fmt(recall5), hitCnt5, expected.size());
            }

        } catch (Exception e) {
            log.error("[{}] Milvus 客户端初始化或关闭失败: {}", TASK_NAME, e.getMessage(), e);
            return EvalTaskResult.error(TASK_NAME, DISPLAY_NAME,
                    "Milvus 客户端异常: " + e.getMessage());
        }

        // ---------- 4. 聚合 ----------
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("mrr@5", fmt(avg(mrr5List)));
        metrics.put("ndcg@5", fmt(avg(ndcg5List)));
        metrics.put("recall@5", fmt(avg(recall5List)));
        metrics.put("hit_rate@5", fmt(evaluatedTotal == 0 ? 0.0 : (double) hit5Cases / evaluatedTotal));
        metrics.put("mrr@10", fmt(avg(mrr10List)));
        metrics.put("ndcg@10", fmt(avg(ndcg10List)));
        metrics.put("recall@10", fmt(avg(recall10List)));
        metrics.put("evaluated_cases", evaluatedTotal);
        metrics.put("embed_fail_cases", embedFailCases);

        String summary = String.format(
                "评估 %d 个 case (其中 %d 个因 embedding/检索失败被排除). " +
                "宽松集合语义: top-K 命中 expected 集合任一元素即算召回. " +
                "K=5 主指标, K=10 辅助. expected 集合规模 7~36, 故 Recall@5 上限受集合大小限制 (典型 ≈ 0.15~0.7).",
                cases.size(), embedFailCases);

        EvalTaskResult result = EvalTaskResult.builder()
                .taskName(TASK_NAME)
                .displayName(DISPLAY_NAME)
                .status(EvalTaskResult.Status.SUCCESS)
                .elapsedMs(System.currentTimeMillis() - t0)
                .totalCount(evaluatedTotal)
                .passCount(passCount)
                .failCount(failCount)
                .metrics(metrics)
                .failureDetails(failureDetails)
                .summary(summary)
                .build();

        log.info("[{}] 完成: MRR@5={} NDCG@5={} Recall@5={} hit_rate@5={} ({} pass / {} fail / {} evaluated)",
                TASK_NAME,
                metrics.get("mrr@5"), metrics.get("ndcg@5"),
                metrics.get("recall@5"), metrics.get("hit_rate@5"),
                passCount, failCount, evaluatedTotal);

        return result;
    }

    // ==================== 辅助 ====================

    private static String loadResource(String name) throws Exception {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IllegalStateException("classpath 找不到资源: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static double avg(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double x : xs) sum += x;
        return sum / xs.size();
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}