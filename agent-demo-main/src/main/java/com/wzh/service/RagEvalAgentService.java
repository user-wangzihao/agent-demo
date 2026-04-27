package com.wzh.service;

import com.wzh.service.MilvusService.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
 
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
 
/**
 * RAG 评估专用检索服务
 *
 * <p><b>设计原则</b>: 完全独立于 {@link AgentService},不影响线上对话主流程。</p>
 *
 * <p><b>职责</b>: 提供"裸检索"能力,只走 embedding + Milvus 向量检索,
 * 不做后处理 (postProcessSearchResults)、不调 LLM、不消耗对话 Token。
 * 评估的目的是衡量"检索系统找到正确 chunk 的能力",
 * 后处理(过滤、去重、截断)会扭曲指标,所以这里不应用。</p>
 *
 * <p><b>扩展路线</b>:
 * <ul>
 *   <li>阶段 0 (当前): 仅 baseline 流水线 — 纯向量检索</li>
 *   <li>阶段 1: 接入 Reranker (gte-rerank-v2),baseline → top-20 → rerank → top-K</li>
 *   <li>阶段 2: 接入 Query Rewriting (qwen-turbo)</li>
 *   <li>阶段 3 (远期): Hybrid Search (升级 Milvus 后)</li>
 * </ul>
 * 每个阶段在 {@link #retrieve(String, String, int)} 里加 case 即可,
 * 不影响其他流水线。</p>
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
 
    /** 流水线类型: 当前线上检索方案 (纯向量) */
    public static final String PIPELINE_BASELINE = "baseline";
 
    // 后续阶段保留扩展位:
    // public static final String PIPELINE_RERANKER = "reranker";
    // public static final String PIPELINE_REWRITING = "rewriting";
    // public static final String PIPELINE_FULL = "rewriting+reranker";
 
    /**
     * 流水线分发入口.
     *
     * @param query    用户问题
     * @param pipeline 流水线类型,见 PIPELINE_* 常量
     * @param topK     返回多少个 chunk_id
     * @return chunk_id 列表,按相关度从高到低排序
     */
    public List<String> retrieve(String query, String pipeline, int topK) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        switch (pipeline) {
            case PIPELINE_BASELINE:
                return retrieveBaseline(query, topK);
 
            // case PIPELINE_RERANKER:
            //     return retrieveWithReranker(query, topK);
            // case PIPELINE_REWRITING:
            //     return retrieveWithRewriting(query, topK);
            // case PIPELINE_FULL:
            //     return retrieveWithFullPipeline(query, topK);
 
            default:
                throw new IllegalArgumentException(
                        "未知 pipeline: " + pipeline + " (阶段 0 仅支持 " + PIPELINE_BASELINE + ")");
        }
    }
 
    // =========================================================================
    // Baseline: 当前线上的检索方案 (纯向量)
    // =========================================================================
 
    /**
     * Baseline 流水线: 纯向量检索,不做后处理.
     *
     * <p>对应 AgentService 中的:
     * <pre>
     *   queryVector = dashScopeService.getEmbedding(message);
     *   searchResults = milvusService.search(queryVector, topK);
     * </pre>
     * 但 <b>不调用 postProcessSearchResults</b> — 评估要测的是原始检索质量.</p>
     */
    private List<String> retrieveBaseline(String query, int topK) {
        try {
            // 1. 文本向量化 (复用 DashScopeService,不重复造轮子)
            List<Float> queryVector = dashScopeService.getEmbedding(query);
            if (queryVector == null || queryVector.isEmpty()) {
                log.warn("[RAG-EVAL] embedding 为空,query={}", query);
                return Collections.emptyList();
            }
 
            // 2. 向量检索 (复用 MilvusService.search)
            List<SearchResult> rawResults = milvusService.search(queryVector, topK);
 
            // 3. 提取 chunk_id 列表 (顺序 = Milvus 返回顺序 = 相关度从高到低)
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
    // 阶段 1+ 扩展位 (注释保留,提示后续接入点)
    // =========================================================================
 
    // private List<String> retrieveWithReranker(String query, int topK) {
    //     // 1. 粗召回: 向量检索 top-20
    //     // 2. 精排: 调 DashScope gte-rerank-v2,返回 top-K
    //     // 3. 失败降级: 返回 baseline 结果
    //     throw new UnsupportedOperationException("阶段 1 实现");
    // }
 
    // private List<String> retrieveWithRewriting(String query, int topK) {
    //     // 1. 调 qwen-turbo 改写出 N 条 query
    //     // 2. 每条 query 各自 Milvus 检索,合并去重
    //     // 3. 失败降级: 用原始 query
    //     throw new UnsupportedOperationException("阶段 2 实现");
    // }
 
    // private List<String> retrieveWithFullPipeline(String query, int topK) {
    //     // Rewriting → 多路召回 → Reranker
    //     throw new UnsupportedOperationException("阶段 2 实现");
    // }
}