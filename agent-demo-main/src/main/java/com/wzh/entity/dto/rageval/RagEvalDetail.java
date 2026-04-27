package com.wzh.entity.dto.rageval;

import lombok.Builder;
import lombok.Data;
 
import java.util.List;
 
/**
 * 单条评估详情
 */
@Data
@Builder
public class RagEvalDetail {
 
    /** 评估集条目 ID */
    private Long evalId;
 
    /** 分类 */
    private String category;
 
    /** 用户问题 */
    private String query;
 
    /** 标注的正确 chunk_id 列表 */
    private List<String> expectedChunks;
 
    /** 实际检索返回的 chunk_id 列表(top-K) */
    private List<String> retrievedChunks;
 
    /**
     * 任一正确 chunk 在检索结果中的最靠前排名(从 1 开始);
     * 没命中为 null
     */
    private Integer hitRank;
 
    /** 是否命中(top-K 内出现任意一个正确 chunk) */
    private Boolean hit;
 
    /**
     * Reciprocal Rank: 1.0/hitRank, 没命中为 0.0;
     * 用于聚合成 MRR
     */
    private Double rr;
 
    /** 本条耗时(ms) */
    private Integer latencyMs;
}