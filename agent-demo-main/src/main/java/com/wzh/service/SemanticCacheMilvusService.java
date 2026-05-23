package com.wzh.service;

import com.alibaba.fastjson.JSONObject;
import com.wzh.config.SemanticCacheProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 语义缓存独立 Milvus collection 服务 (第3刀 L2 层).
 *
 * <p><b>Collection</b>: {@code semantic_cache_vectors} (与 feature_document_vectors / faq_vectors 完全隔离).
 * Collection 名走 {@link SemanticCacheProperties#getMilvusCollection()}, 不污染 {@link com.wzh.config.MilvusConfig}.</p>
 *
 * <p><b>Schema</b>:</p>
 * <ul>
 *   <li>cache_key (VarChar 64, 主键 autoID=false) — 反查 MySQL/Redis 的关键字段, 业务层保证唯一</li>
 *   <li>feature_name (VarChar 100) — ANN 检索时按 featureName filter, 避免跨 feature 误命中</li>
 *   <li>vector (FloatVector 1024) — DashScope text-embedding-v3 维度</li>
 *   <li>expire_at_ms (Int64) — 过期时间戳毫秒, 定时清理用</li>
 * </ul>
 *
 * <p><b>Index</b>: vector 字段 IVF_FLAT + COSINE (与 feature_document_vectors / faq_vectors 一致).</p>
 *
 * <p><b>v2 API 风格</b>: 与 {@link FaqMilvusService} 完全对齐 (MilvusClientV2 + JSONObject rows + builder Req).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheMilvusService {

    private static final int VECTOR_DIM = 1024;

    private static final String F_CACHE_KEY = "cache_key";
    private static final String F_FEATURE_NAME = "feature_name";
    private static final String F_VECTOR = "vector";
    private static final String F_EXPIRE_AT_MS = "expire_at_ms";

    private final MilvusClientV2 milvusClient;
    private final SemanticCacheProperties properties;

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[SemanticCacheMilvus] disabled, skip init");
            return;
        }
        try {
            String collectionName = properties.getMilvusCollection();
            boolean exists = milvusClient.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build());
            if (exists) {
                log.info("[SemanticCacheMilvus] collection [{}] 已存在", collectionName);
                return;
            }

            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();

            // cache_key: 主键 (业务层 MD5 保证唯一, autoID=false)
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_CACHE_KEY)
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build());

            // feature_name: ANN 检索按此 filter
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_FEATURE_NAME)
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .build());

            // vector: query embedding
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_VECTOR)
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());

            // expire_at_ms: 过期时间戳毫秒
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_EXPIRE_AT_MS)
                    .dataType(DataType.Int64)
                    .build());

            List<IndexParam> indexParams = new ArrayList<>();
            indexParams.add(IndexParam.builder()
                    .fieldName(F_VECTOR)
                    .indexType(IndexParam.IndexType.IVF_FLAT)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("nlist", 128))
                    .build());

            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build());

            log.info("[SemanticCacheMilvus] collection [{}] 创建成功", collectionName);
        } catch (Exception e) {
            log.error("[SemanticCacheMilvus] init failed", e);
        }
    }

    // ==================== 写入 ====================

    /** 插入一条向量. 业务层保证 cacheKey 唯一. */
    public void insert(String cacheKey, String featureName, List<Float> embedding, long expireAtMs) {
        if (embedding == null || embedding.size() != VECTOR_DIM) {
            log.warn("[SemanticCacheMilvus] invalid embedding size, skip insert cacheKey={}", cacheKey);
            return;
        }
        try {
            JSONObject row = new JSONObject();
            row.put(F_CACHE_KEY, cacheKey);
            row.put(F_FEATURE_NAME, featureName);
            row.put(F_VECTOR, embedding);
            row.put(F_EXPIRE_AT_MS, expireAtMs);

            milvusClient.insert(InsertReq.builder()
                    .collectionName(properties.getMilvusCollection())
                    .data(Collections.singletonList(row))
                    .build());
        } catch (Exception e) {
            log.warn("[SemanticCacheMilvus] insert failed cacheKey={}", cacheKey, e);
        }
    }

    // ==================== ANN 搜索 ====================

    /**
     * 按 featureName filter + 余弦相似度 ANN 搜索 top1.
     *
     * @return 命中且 similarity ≥ threshold 才返回; 否则 null
     */
    public Hit searchTopOne(List<Float> queryEmbedding, String featureName, double threshold) {
        if (queryEmbedding == null || queryEmbedding.size() != VECTOR_DIM) return null;

        try {
            String filterExpr = String.format("%s == \"%s\"", F_FEATURE_NAME, escape(featureName));
            SearchReq req = SearchReq.builder()
                    .collectionName(properties.getMilvusCollection())
                    .data(Collections.singletonList(queryEmbedding))
                    .topK(1)
                    .filter(filterExpr)
                    .outputFields(List.of(F_CACHE_KEY))
                    .build();
            SearchResp resp = milvusClient.search(req);

            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            if (results == null || results.isEmpty() || results.get(0).isEmpty()) return null;

            SearchResp.SearchResult top = results.get(0).get(0);
            float sim = top.getDistance() == null ? -1f : top.getDistance().floatValue();
            if (sim < threshold) return null;

            Map<String, Object> entity = top.getEntity();
            String cacheKey = entity == null ? null : String.valueOf(entity.get(F_CACHE_KEY));
            if (cacheKey == null || "null".equals(cacheKey) || cacheKey.isEmpty()) return null;

            return new Hit(cacheKey, sim);
        } catch (Exception e) {
            log.warn("[SemanticCacheMilvus] search failed featureName={}", featureName, e);
            return null;
        }
    }

    // ==================== 删除 ====================

    /** 按 cacheKey 删一条. */
    public void deleteByCacheKey(String cacheKey) {
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(properties.getMilvusCollection())
                    .filter(String.format("%s == \"%s\"", F_CACHE_KEY, escape(cacheKey)))
                    .build());
        } catch (Exception e) {
            log.warn("[SemanticCacheMilvus] delete failed cacheKey={}", cacheKey, e);
        }
    }

    /** 按 featureName 批量删 (管理员失效用). */
    public void deleteByFeatureName(String featureName) {
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(properties.getMilvusCollection())
                    .filter(String.format("%s == \"%s\"", F_FEATURE_NAME, escape(featureName)))
                    .build());
        } catch (Exception e) {
            log.warn("[SemanticCacheMilvus] delete by feature failed featureName={}", featureName, e);
        }
    }

    /** 删过期向量 (定时任务调用). */
    public void deleteExpired(long nowMs) {
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(properties.getMilvusCollection())
                    .filter(String.format("%s < %d", F_EXPIRE_AT_MS, nowMs))
                    .build());
        } catch (Exception e) {
            log.warn("[SemanticCacheMilvus] delete expired failed", e);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    /** ANN 命中结果. */
    @Data
    @AllArgsConstructor
    public static class Hit {
        private String cacheKey;
        private double similarity;
    }
}