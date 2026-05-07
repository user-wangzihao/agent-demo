package com.wzh.agentdemo.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
 
import java.math.BigDecimal;
import java.time.LocalDateTime;
 
/**
 * RAG 评估运行结果
 *
 * <p>每次跑评估留一条记录,用于不同 pipeline 的横向对比
 * (baseline / reranker / rewriting / rewriting+reranker)。</p>
 */
@Data
@TableName("rag_eval_run")
public class RagEvalRun {
 
    @TableId(type = IdType.AUTO)
    private Long id;
 
    /** baseline / reranker / rewriting / rewriting+reranker */
    private String pipeline;
 
    /** 本次跑了多少条 */
    private Integer totalCount;
 
    /** Hit@3 命中率 */
    @TableField("hit_at_3")
    private BigDecimal hitAt3;
 
    /** MRR@5 */
    @TableField("mrr_at_5")
    private BigDecimal mrrAt5;

    /** NDCG@5 指标 */
    @TableField("ndcg_at_5")
    private BigDecimal ndcgAt5;
 
    /** 平均检索耗时(ms) */
    private Integer avgLatencyMs;
 
    /** 每条 query 的命中详情 JSON */
    private String detailJson;
 
    /** 本次运行的关键参数快照 */
    private String configSnapshot;
 
    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT, value = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}