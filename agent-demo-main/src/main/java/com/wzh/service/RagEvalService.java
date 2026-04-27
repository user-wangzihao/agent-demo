package com.wzh.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.RagEvalRun;
import com.wzh.agentdemo.common.entity.RagEvalSet;
import com.wzh.agentdemo.common.mapper.RagEvalRunMapper;
import com.wzh.agentdemo.common.mapper.RagEvalSetMapper;
import com.wzh.entity.dto.rageval.RagEvalDetail;
import com.wzh.entity.dto.rageval.RagEvalRunRequest;
import com.wzh.entity.dto.rageval.RagEvalRunResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
 
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
 
/**
 * RAG 评估服务 — 编排层
 *
 * <p>职责: 加载评估集 → 调 {@link RagEvalAgentService} 跑检索 →
 * 计算 Hit@3 / MRR@5 → 持久化到 rag_eval_run.</p>
 *
 * <p>与 {@link AgentService} 完全解耦,不影响线上对话流程.</p>
 *
 * @author wzh
 * @since 2026-04-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvalService {
 
    private final RagEvalSetMapper evalSetMapper;
    private final RagEvalRunMapper evalRunMapper;
    private final RagEvalAgentService ragEvalAgentService;
    private final ObjectMapper objectMapper;
 
    /** 默认 top-K */
    private static final int DEFAULT_TOP_K = 5;
    /** Hit@K 中的 K */
    private static final int HIT_K = 3;
    /** MRR@K 中的 K */
    private static final int MRR_K = 5;
 
    /**
     * 跑一次评估
     */
    public RagEvalRunResponse run(RagEvalRunRequest request) {
        // 1. 加载评估集
        List<RagEvalSet> evalSets = loadEvalSets(request.getEvalSetIds());
        if (evalSets.isEmpty()) {
            throw new IllegalStateException("评估集为空,请先初始化 rag_eval_set 表数据");
        }
 
        String pipeline = request.getPipeline() == null
                ? RagEvalAgentService.PIPELINE_BASELINE
                : request.getPipeline();
        int topK = request.getTopK() == null ? DEFAULT_TOP_K : request.getTopK();
 
        log.info("[RAG-EVAL] 开始评估 pipeline={} topK={} 总条数={}",
                pipeline, topK, evalSets.size());
 
        // 2. 逐条跑检索 + 计算单条指标
        List<RagEvalDetail> details = new ArrayList<>();
        long totalLatency = 0;
        for (RagEvalSet evalSet : evalSets) {
            RagEvalDetail detail = evalOne(evalSet, pipeline, topK);
            details.add(detail);
            totalLatency += (detail.getLatencyMs() == null ? 0 : detail.getLatencyMs());
        }
 
        // 3. 聚合指标
        int hitCount = (int) details.stream()
                .filter(d -> Boolean.TRUE.equals(d.getHit())).count();
        BigDecimal hitAt3 = computeHitAtK(details, HIT_K);
        BigDecimal mrrAt5 = computeMrrAtK(details, MRR_K);
        int avgLatency = details.isEmpty() ? 0 : (int) (totalLatency / details.size());
 
        // 4. 持久化
        Long runId = saveRunRecord(pipeline, details.size(), hitAt3, mrrAt5, avgLatency,
                details, topK);
 
        log.info("[RAG-EVAL] 评估完成 pipeline={} Hit@3={} MRR@5={} avgLatency={}ms hitCount={}/{}",
                pipeline, hitAt3, mrrAt5, avgLatency, hitCount, details.size());
 
        return RagEvalRunResponse.builder()
                .runId(runId)
                .pipeline(pipeline)
                .totalCount(details.size())
                .hitCount(hitCount)
                .hitAt3(hitAt3)
                .mrrAt5(mrrAt5)
                .avgLatencyMs(avgLatency)
                .details(details)
                .build();
    }
 
    // =========================================================================
    // 单条评估
    // =========================================================================
 
    private RagEvalDetail evalOne(RagEvalSet evalSet, String pipeline, int topK) {
        Set<String> expected = parseChunkIds(evalSet.getExpectedChunks());
        long start = System.currentTimeMillis();
        List<String> retrieved;
        try {
            // top-K 取 Math.max(topK, MRR_K) 以确保 MRR@5 能正确计算
            int actualTopK = Math.max(topK, MRR_K);
            retrieved = ragEvalAgentService.retrieve(evalSet.getQuery(), pipeline, actualTopK);
        } catch (Exception e) {
            log.error("[RAG-EVAL] 检索异常 evalId={} query={}",
                    evalSet.getId(), evalSet.getQuery(), e);
            retrieved = new ArrayList<>();
        }
        int latency = (int) (System.currentTimeMillis() - start);
 
        // hitRank: 任一正确 chunk 在 retrieved 中的最靠前排名(从 1 开始)
        Integer hitRank = null;
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                hitRank = i + 1;
                break;
            }
        }
 
        boolean hit = hitRank != null && hitRank <= topK;
        double rr = hitRank == null ? 0.0 : 1.0 / hitRank;
 
        return RagEvalDetail.builder()
                .evalId(evalSet.getId())
                .category(evalSet.getCategory())
                .query(evalSet.getQuery())
                .expectedChunks(new ArrayList<>(expected))
                .retrievedChunks(retrieved)
                .hitRank(hitRank)
                .hit(hit)
                .rr(rr)
                .latencyMs(latency)
                .build();
    }
 
    // =========================================================================
    // 指标计算
    // =========================================================================
 
    /** Hit@K = 命中数 / 总数 */
    private BigDecimal computeHitAtK(List<RagEvalDetail> details, int k) {
        if (details.isEmpty()) return BigDecimal.ZERO;
        long hitCount = details.stream()
                .filter(d -> d.getHitRank() != null && d.getHitRank() <= k)
                .count();
        return BigDecimal.valueOf(hitCount)
                .divide(BigDecimal.valueOf(details.size()), 4, RoundingMode.HALF_UP);
    }
 
    /** MRR@K = 平均(1 / hitRank) */
    private BigDecimal computeMrrAtK(List<RagEvalDetail> details, int k) {
        if (details.isEmpty()) return BigDecimal.ZERO;
        double sum = details.stream()
                .mapToDouble(d -> {
                    Integer rank = d.getHitRank();
                    return (rank == null || rank > k) ? 0.0 : 1.0 / rank;
                })
                .sum();
        return BigDecimal.valueOf(sum / details.size())
                .setScale(4, RoundingMode.HALF_UP);
    }
 
    // =========================================================================
    // 持久化与工具方法
    // =========================================================================
 
    private List<RagEvalSet> loadEvalSets(List<Long> evalSetIds) {
        LambdaQueryWrapper<RagEvalSet> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagEvalSet::getEnabled, 1);
        if (evalSetIds != null && !evalSetIds.isEmpty()) {
            wrapper.in(RagEvalSet::getId, evalSetIds);
        }
        wrapper.orderByAsc(RagEvalSet::getId);
        return evalSetMapper.selectList(wrapper);
    }
 
    private Set<String> parseChunkIds(String csv) {
        if (csv == null || csv.isEmpty()) return new HashSet<>();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
 
    private Long saveRunRecord(String pipeline, int total, BigDecimal hitAt3, BigDecimal mrrAt5,
                               int avgLatency, List<RagEvalDetail> details, int topK) {
        RagEvalRun run = new RagEvalRun();
        run.setPipeline(pipeline);
        run.setTotalCount(total);
        run.setHitAt3(hitAt3);
        run.setMrrAt5(mrrAt5);
        run.setAvgLatencyMs(avgLatency);
        try {
            run.setDetailJson(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            log.warn("[RAG-EVAL] detail 序列化失败", e);
            run.setDetailJson("[]");
        }
        run.setConfigSnapshot(String.format("{\"topK\":%d,\"hitK\":%d,\"mrrK\":%d}",
                topK, HIT_K, MRR_K));
        evalRunMapper.insert(run);
        return run.getId();
    }
}