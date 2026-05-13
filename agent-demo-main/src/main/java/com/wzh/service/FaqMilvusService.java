package com.wzh.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.wzh.config.MilvusConfig;
import com.wzh.service.MilvusService.ChunkData;
import com.wzh.service.MilvusService.SearchResult;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * FAQ 专属 Milvus 服务 (第四刀引入).
 *
 * <p><b>为什么独立于 MilvusService</b>: MilvusService 当前是单 collection 设计
 * (init / insert / search 都默认操作 milvus.collectionName). 强行让它支持
 * 双 collection 会让所有方法变成"按 collection 分支", 污染老链路代码.
 * 独立 Service + 独立 collection 是更稳的工程选择, 也对齐
 * "独立 collection > 过滤" 的架构决策.</p>
 *
 * <p><b>schema 复用策略</b>: 字段几乎对齐 feature_document_vectors, 仅:
 * <ul>
 *   <li>主键 chunk_id (UUID, 沿用现有风格)</li>
 *   <li>doc_id 改为 faq_id (语义明确; 不再用负数 docId 这种临时约定)</li>
 *   <li>chunk_type: faq_qa / image_description (沿用现有约定)</li>
 *   <li>其他字段 (feature_name / content / image_urls / vector) 完全对齐</li>
 * </ul>
 * SearchResult POJO 直接复用 MilvusService.SearchResult (其 docId 字段在
 * FAQ 语义下承载 faqId; POJO 不感知字段名差异).</p>
 *
 * @author wzh
 * @since 2026-05-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqMilvusService {

    private final MilvusClientV2 milvusClient;
    private final MilvusConfig milvusConfig;

    /** text-embedding-v3 维度, 与主 collection 保持一致 */
    private static final int VECTOR_DIM = 1024;

    /**
     * 初始化: 确保 faq_vectors collection 存在.
     *
     * <p>启动时执行, 与 MilvusService.init() 行为对称.</p>
     */
    @PostConstruct
    public void init() {
        try {
            String collectionName = milvusConfig.getFaqCollectionName();
            boolean exists = milvusClient.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build());

            if (exists) {
                log.info("Milvus FAQ collection [{}] 已存在", collectionName);
                return;
            }

            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();

            // chunk_id: 主键 (UUID, 沿用 IdUtil.fastSimpleUUID 风格)
            schema.addField(AddFieldReq.builder()
                    .fieldName("chunk_id")
                    .dataType(DataType.VarChar)
                    .maxLength(128)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build());

            // faq_id: 关联 faq_document.id (正整数, 取代负 docId 约定)
            schema.addField(AddFieldReq.builder()
                    .fieldName("faq_id")
                    .dataType(DataType.Int64)
                    .build());

            // chunk_type: faq_qa / image_description
            schema.addField(AddFieldReq.builder()
                    .fieldName("chunk_type")
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .build());

            // feature_name: 所属功能; 通用 FAQ = "通用FAQ"
            schema.addField(AddFieldReq.builder()
                    .fieldName("feature_name")
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .build());

            // content: chunk 文本内容
            schema.addField(AddFieldReq.builder()
                    .fieldName("content")
                    .dataType(DataType.VarChar)
                    .maxLength(8192)
                    .build());

            // image_urls: JSON 数组字符串 (对齐 feature_document_vectors 风格)
            schema.addField(AddFieldReq.builder()
                    .fieldName("image_urls")
                    .dataType(DataType.VarChar)
                    .maxLength(4096)
                    .build());

            // vector: 向量字段
            schema.addField(AddFieldReq.builder()
                    .fieldName("vector")
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());

            // 索引: 与主 collection 对齐 (IVF_FLAT + COSINE + nlist=128)
            List<IndexParam> indexParams = new ArrayList<>();
            indexParams.add(IndexParam.builder()
                    .fieldName("vector")
                    .indexType(IndexParam.IndexType.IVF_FLAT)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("nlist", 128))
                    .build());

            CreateCollectionReq createReq = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build();

            milvusClient.createCollection(createReq);
            log.info("Milvus FAQ collection [{}] 创建成功", collectionName);
        } catch (Exception e) {
            log.error("FAQ collection 初始化失败", e);
        }
    }

    /**
     * 插入 FAQ chunks.
     *
     * <p><b>注意 ChunkData.docId 语义</b>: 在 FAQ 上下文里, docId 字段承载 faqId
     * (复用 POJO 不引入新类; 通过插入时映射到 faq_id 字段实现 schema 隔离).</p>
     */
    public void insertFaqChunks(List<ChunkData> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        String collectionName = milvusConfig.getFaqCollectionName();
        List<JSONObject> rows = new ArrayList<>();

        for (ChunkData chunk : chunks) {
            JSONObject row = new JSONObject();
            row.put("chunk_id", chunk.chunkId);
            row.put("faq_id", chunk.docId);   // ← docId 在 FAQ 语义下即 faqId
            row.put("chunk_type", chunk.chunkType);
            row.put("feature_name", chunk.featureName);
            row.put("content", chunk.content);
            row.put("image_urls", JSON.toJSONString(chunk.imageUrls));
            row.put("vector", chunk.vector);
            rows.add(row);
        }

        milvusClient.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());

        log.info("成功插入 {} 条 FAQ 向量到 [{}]", chunks.size(), collectionName);
    }

    /**
     * 按 faqId 删除一条 FAQ 的所有 chunk (重新学习时先清旧数据 / 删除 FAQ 时联动清理).
     */
    public void deleteByFaqId(Long faqId) {
        String collectionName = milvusConfig.getFaqCollectionName();
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter("faq_id == " + faqId)
                .build());
        log.info("已删除 FAQ {} 的所有向量数据", faqId);
    }

    /**
     * FAQ 向量检索.
     *
     * <p><b>过滤策略</b> (与 FaqRetrieveNode 的判定保持同步):
     * <ul>
     *   <li>matchedFeature 非空 → feature_name in [matchedFeature, generalMarker]</li>
     *   <li>matchedFeature 为空 → feature_name == generalMarker (只召回通用 FAQ)</li>
     * </ul>
     * 这里只负责"按给定 feature 列表搜", 调用方 (FaqRetrieveNode) 负责
     * 根据 matchedFeature 决定传哪些值.</p>
     *
     * @param queryVector 查询向量
     * @param features    要过滤的 feature_name 列表; null/空 = 不过滤 (理论不发生)
     * @param topK        返回的最相似结果数量
     * @return 搜索结果 (字段语义: SearchResult.docId = faqId)
     */
    public List<SearchResult> searchFaq(List<Float> queryVector,
                                        List<String> features,
                                        int topK) {
        String collectionName = milvusConfig.getFaqCollectionName();

        SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(queryVector))
                .topK(topK)
                .outputFields(Arrays.asList("chunk_id", "faq_id", "chunk_type",
                        "feature_name", "content", "image_urls"));

        String filterExpr = buildFeatureFilterExpr(features);
        if (filterExpr != null) {
            builder.filter(filterExpr);
            log.info("[FAQ-MILVUS-SEARCH] 启用 feature 过滤 expr={} topK={}", filterExpr, topK);
        } else {
            log.info("[FAQ-MILVUS-SEARCH] 无 feature 过滤, 全 FAQ 检索 topK={}", topK);
        }

        SearchResp searchResp = milvusClient.search(builder.build());

        List<SearchResult> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();
        if (searchResults != null && !searchResults.isEmpty()) {
            for (SearchResp.SearchResult item : searchResults.get(0)) {
                SearchResult sr = new SearchResult();
                Map<String, Object> entity = item.getEntity();
                sr.chunkId = String.valueOf(entity.get("chunk_id"));
                sr.docId = Long.parseLong(String.valueOf(entity.get("faq_id")));   // 读 faq_id
                sr.chunkType = String.valueOf(entity.get("chunk_type"));
                sr.featureName = String.valueOf(entity.get("feature_name"));
                sr.content = String.valueOf(entity.get("content"));
                sr.imageUrls = String.valueOf(entity.get("image_urls"));
                sr.score = item.getDistance();
                results.add(sr);
            }
        }
        return results;
    }

    /**
     * 构造 feature_name 过滤表达式 (与 MilvusService.buildFeatureFilterExpr 同义).
     */
    private String buildFeatureFilterExpr(List<String> features) {
        if (features == null || features.isEmpty()) return null;
        List<String> valid = features.stream()
                .filter(f -> f != null && !f.trim().isEmpty())
                .map(f -> "\"" + f.replace("\"", "\\\"") + "\"")
                .toList();
        if (valid.isEmpty()) return null;
        return "feature_name in [" + String.join(",", valid) + "]";
    }
}