package com.wzh.entity.dto.rageval;

import lombok.Builder;
import lombok.Data;
 
import java.math.BigDecimal;
import java.util.List;
 
/**
 * 评估接口响应
 */
@Data
@Builder
public class RagEvalRunResponse {
 
    /** 本次运行 ID(对应 rag_eval_run 表主键) */
    private Long runId;
 
    /** 流水线类型 */
    private String pipeline;
 
    /** 总条数 */
    private Integer totalCount;
 
    /** 命中条数(top-K 内有任意正确 chunk) */
    private Integer hitCount;
 
    /** Hit@3 */
    private BigDecimal hitAt3;
 
    /** MRR@5 */
    private BigDecimal mrrAt5;
 
    /** 平均检索耗时(ms) */
    private Integer avgLatencyMs;
 
    /** 每条 query 的详细命中情况 */
    private List<RagEvalDetail> details;
}