package com.wzh.agentdemo.evaltools.milvus;

import com.google.gson.JsonObject;
import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.model.ChunkCandidate;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.GetResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 读取器，独立于生产 MilvusService，仅做评估期检索。
 *
 * <p>提供三种能力：</p>
 * <ol>
 *   <li>向量 Top-K 检索</li>
 *   <li>关键词预筛 (content like %kw%)</li>
 *   <li>按 chunkId 列表批量取详情</li>
 * </ol>
 */
@Slf4j
public class MilvusBulkReader implements AutoCloseable {

    private final MilvusClientV2 client;
    private final String collection;

    public MilvusBulkReader() {
        this.client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + AuditConfig.MILVUS_HOST + ":" + AuditConfig.MILVUS_PORT)
                .build());
        this.collection = AuditConfig.MILVUS_COLLECTION;
        log.info("Milvus client connected to {}:{} collection={}",
                AuditConfig.MILVUS_HOST, AuditConfig.MILVUS_PORT, collection);
    }

    /**
     * 向量 Top-K 检索，返回带 rank/score 的 ChunkCandidate 列表。
     */
    public List<ChunkCandidate> vectorSearch(List<Float> queryVector, int topK) {
        SearchReq req = SearchReq.builder()
                .collectionName(collection)
                .data(Collections.singletonList(new FloatVec(queryVector)))
                .topK(topK)
                .outputFields(List.of("chunk_id", "content", "feature_name", "chunk_type"))
                .build();

        SearchResp resp = client.search(req);
        List<ChunkCandidate> result = new ArrayList<>();
        if (resp.getSearchResults().isEmpty()) return result;

        List<SearchResp.SearchResult> hits = resp.getSearchResults().get(0);
        for (int i = 0; i < hits.size(); i++) {
            SearchResp.SearchResult hit = hits.get(i);
            Map<String, Object> entity = hit.getEntity();

            ChunkCandidate cand = ChunkCandidate.builder()
                    .chunkId(strVal(hit.getId()))
                    .content(strVal(entity.get("content")))
                    .featureName(strVal(entity.get("feature_name")))
                    .chunkType(strVal(entity.get("chunk_type")))
                    .source("VECTOR")
                    .vectorRank(i + 1)
                    .vectorScore(hit.getScore() == null ? null : hit.getScore().doubleValue())
                    .build();
            result.add(cand);
        }
        return result;
    }

    /**
     * 关键词预筛：用 OR 连接的 like 表达式，命中任一关键词即返回。
     * <p>结果数量超过 limit 时仅保留前 limit 条 (顺序由 Milvus 决定)。</p>
     */
    public List<ChunkCandidate> keywordPrefilter(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) return List.of();

        // 构造 filter: content like "%kw1%" or content like "%kw2%" ...
        String filter = keywords.stream()
                .map(this::escapeForFilter)
                .map(k -> "content like \"%" + k + "%\"")
                .collect(Collectors.joining(" or "));

        QueryReq req = QueryReq.builder()
                .collectionName(collection)
                .filter(filter)
                .outputFields(List.of("chunk_id", "content", "feature_name", "chunk_type"))
                .limit((long) limit)
                .build();

        QueryResp resp;
        try {
            resp = client.query(req);
        } catch (Exception e) {
            log.warn("关键词预筛失败 keywords={}, filter={}, err={}", keywords, filter, e.getMessage());
            return List.of();
        }

        List<ChunkCandidate> result = new ArrayList<>();
        for (QueryResp.QueryResult r : resp.getQueryResults()) {
            Map<String, Object> entity = r.getEntity();
            ChunkCandidate cand = ChunkCandidate.builder()
                    .chunkId(strVal(entity.get("chunk_id")))
                    .content(strVal(entity.get("content")))
                    .featureName(strVal(entity.get("feature_name")))
                    .chunkType(strVal(entity.get("chunk_type")))
                    .source("KEYWORD")
                    .build();
            result.add(cand);
        }
        return result;
    }

    /**
     * 按 chunkId 批量取 chunk 详情 (用于 expectedChunks 信息回填)。
     */
    public Map<String, ChunkCandidate> getByIds(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();

        GetReq req = GetReq.builder()
                .collectionName(collection)
                .ids(new ArrayList<>(chunkIds))
                .outputFields(List.of("chunk_id", "content", "feature_name", "chunk_type"))
                .build();

        GetResp resp = client.get(req);
        Map<String, ChunkCandidate> map = new LinkedHashMap<>();
        for (var r : resp.getGetResults()) {
            Map<String, Object> entity = r.getEntity();
            String id = strVal(entity.get("chunk_id"));
            map.put(id, ChunkCandidate.builder()
                    .chunkId(id)
                    .content(strVal(entity.get("content")))
                    .featureName(strVal(entity.get("feature_name")))
                    .chunkType(strVal(entity.get("chunk_type")))
                    .source("LOOKUP")
                    .build());
        }
        return map;
    }

    private String escapeForFilter(String kw) {
        // Milvus filter 字符串里的 " 和 \ 都要转义
        return kw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String strVal(Object o) {
        return o == null ? "" : o.toString();
    }

    @Override
    public void close() {
        try {
            // MilvusClientV2.close(long maxWaitSeconds) - 等待最多 5 秒优雅关闭
            client.close(5L);
        } catch (Exception e) {
            log.warn("关闭 Milvus 客户端失败: {}", e.getMessage());
        }
    }
}