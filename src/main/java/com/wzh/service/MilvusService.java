package com.wzh.service;

import com.wzh.config.MilvusConfig;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.milvus.param.dml.DeleteParam;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusClientV2 milvusClient;
    private final MilvusConfig milvusConfig;

    private static final int VECTOR_DIM = 1024; // text-embedding-v3 维度

    /**
     * 初始化：确保 Collection 存在
     */
    @PostConstruct
    public void init() {
        try {
            String collectionName = milvusConfig.getCollectionName();
            boolean exists = milvusClient.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build());

            if (!exists) {
                // 定义字段
                CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();

                // chunk_id: 主键
                schema.addField(AddFieldReq.builder()
                        .fieldName("chunk_id")
                        .dataType(DataType.VarChar)
                        .maxLength(128)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build());

                // doc_id: 关联的功能文档ID
                schema.addField(AddFieldReq.builder()
                        .fieldName("doc_id")
                        .dataType(DataType.Int64)
                        .build());

                // chunk_type: 块类型
                schema.addField(AddFieldReq.builder()
                        .fieldName("chunk_type")
                        .dataType(DataType.VarChar)
                        .maxLength(64)
                        .build());

                // feature_name: 所属功能名称
                schema.addField(AddFieldReq.builder()
                        .fieldName("feature_name")
                        .dataType(DataType.VarChar)
                        .maxLength(256)
                        .build());

                // content: 文本内容
                schema.addField(AddFieldReq.builder()
                        .fieldName("content")
                        .dataType(DataType.VarChar)
                        .maxLength(8192)
                        .build());

                // image_urls: 关联的图片URL（JSON数组字符串）
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

                // 索引
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
                log.info("Milvus collection [{}] 创建成功", collectionName);
            } else {
                log.info("Milvus collection [{}] 已存在", collectionName);
            }
        } catch (Exception e) {
            log.error("Milvus 初始化失败", e);
        }
    }

    /**
     * 插入向量数据
     */
    public void insertChunks(List<ChunkData> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        String collectionName = milvusConfig.getCollectionName();
        List<JSONObject> rows = new ArrayList<>();

        for (ChunkData chunk : chunks) {
            JSONObject row = new JSONObject();
            row.put("chunk_id", chunk.chunkId);
            row.put("doc_id", chunk.docId);
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

        log.info("成功插入 {} 条向量数据到 Milvus", chunks.size());
    }

    /**
     * 根据文档ID删除所有相关向量（重新学习时先清除旧数据）
     */
    public void deleteByDocId(Long docId) {
        String collectionName = milvusConfig.getCollectionName();
        milvusClient.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter("doc_id == " + docId)
                .build());
        log.info("已删除文档 {} 的所有向量数据", docId);
    }

    /**
     * 向量相似度搜索
     *
     * @param queryVector 查询向量
     * @param topK        返回的最相似结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> search(List<Float> queryVector, int topK) {
        String collectionName = milvusConfig.getCollectionName();

        SearchResp searchResp = milvusClient.search(SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(queryVector))
                .topK(topK)
                .outputFields(Arrays.asList("chunk_id", "doc_id", "chunk_type", "feature_name", "content", "image_urls"))
                .build());

        List<SearchResult> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();
        if (searchResults != null && !searchResults.isEmpty()) {
            for (SearchResp.SearchResult item : searchResults.get(0)) {
                SearchResult sr = new SearchResult();
                Map<String, Object> entity = item.getEntity();
                sr.chunkId = String.valueOf(entity.get("chunk_id"));
                sr.docId = Long.parseLong(String.valueOf(entity.get("doc_id")));
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
     * 删除指定文档的指定类型的 chunk
     */
    public void deleteByDocIdAndChunkType(Long docId, String chunkType) {
        try {
            String collectionName = milvusConfig.getCollectionName();
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(String.format("doc_id == %d and chunk_type == \"%s\"", docId, chunkType))
                    .build());
            log.info("已删除文档 [{}] 的 [{}] 类型 chunk", docId, chunkType);
        } catch (Exception e) {
            log.error("删除 chunk 失败", e);
        }
    }

    // ========== 数据类 ==========

    /**
     * 待插入的文档块数据
     */
    public static class ChunkData {
        public String chunkId;
        public Long docId;
        public String chunkType;
        public String featureName;
        public String content;
        public List<String> imageUrls;
        public List<Float> vector;
    }

    /**
     * 搜索结果
     */
    public static class SearchResult {
        public String chunkId;
        public Long docId;
        public String chunkType;
        public String featureName;
        public String content;
        public String imageUrls;
        public float score;
    }
}