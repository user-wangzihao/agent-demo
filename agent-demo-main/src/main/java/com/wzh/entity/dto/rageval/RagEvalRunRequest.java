package com.wzh.entity.dto.rageval;

import lombok.Data;
 
import java.util.List;
 
/**
 * 评估接口请求体
 */
@Data
public class RagEvalRunRequest {
 
    /**
     * 流水线类型:
     * - baseline: 当前线上检索 (向量检索 top-K)
     * - reranker: 向量检索 top-20 -> Reranker top-K
     * - rewriting: Query Rewriting 后向量检索 top-K
     * - rewriting+reranker: 全流程
     * - feature_aware: 带特征向量检索
     *
     * <p>阶段 0 只支持 baseline,后续接入 Reranker/Rewriting 后扩展。</p>
     */
    private String pipeline = "baseline";
 
    /** 评估时检索的 top-K, 默认 5 */
    private Integer topK = 5;
 
    /**
     * 跑评估集子集时指定 ID 列表;
     * null 或空则跑全量启用的 (enabled=1) 评估集
     */
    private List<Long> evalSetIds;
}
 