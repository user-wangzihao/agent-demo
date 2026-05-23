package com.wzh.agentdemo.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语义缓存元数据表实体（第3刀 L1+L2 双层缓存）.
 *
 * <p>本表是治理元数据层，与另外两个存储层配合工作：</p>
 * <ul>
 *   <li><b>Redis</b> {@code cache:answer:{cacheKey}}：存 answer + sourceInfo 正文，TTL 24h</li>
 *   <li><b>Milvus</b> {@code semantic_cache_vectors} collection：存 query embedding，L2 ANN 检索用</li>
 *   <li><b>MySQL</b>（本表）：状态机 + 命中统计 + 负反馈分 + 过期治理</li>
 * </ul>
 *
 * <p><b>命中链路</b>：</p>
 * <ol>
 *   <li>L1：直接用 {@code MD5(featureName + query)} 算 cacheKey 查 Redis</li>
 *   <li>L2：query 向量化 → Milvus ANN → 拿 cacheKey → 查 Redis answer + 查本表 status</li>
 *   <li>本表 status=ACTIVE 才视为真命中；DEGRADED/INVALID 跳过缓存重新生成</li>
 * </ol>
 *
 * <p><b>失效来源</b>：TTL 自然到期 / 管理员重学文档(INVALID) / 负反馈累加(DEGRADED)</p>
 */
@Data
@TableName("semantic_cache")
public class SemanticCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 缓存唯一键 = MD5(featureName + 归一化后的 query).
     * L1 精确命中和 L2 语义命中都通过此键定位本表记录.
     */
    private String cacheKey;

    /**
     * 所属功能名. 用于管理员重学文档 / 操作 FAQ 时按 featureName 批量失效.
     */
    private String featureName;

    /**
     * 原始用户问题. 用于人工审查"哪些缓存被 DEGRADED"以及事后归因.
     */
    private String queryText;

    /**
     * 缓存的 AI 回答全文. 命中后直接返回给前端, 跳过 LLM 调用.
     */
    private String answerText;

    /**
     * JSON 序列化的 SourceInfo 列表. 命中时随回答返回, 用于"引用来源"展示.
     */
    private String sourceInfo;

    /**
     * 状态机 (取值见 {@link CacheStatus}):
     * <ul>
     *   <li>ACTIVE   = 正常可命中</li>
     *   <li>DEGRADED = 负反馈超阈值, 命中时跳过缓存重新生成 (新回答写回时恢复 ACTIVE + 清零 feedback_score)</li>
     *   <li>INVALID  = 管理员主动失效, 永不命中</li>
     * </ul>
     */
    private String status;

    /**
     * 命中次数. 每次 L1 或 L2 命中且 status=ACTIVE 后 +1.
     */
    private Integer hitCount;

    /**
     * 负反馈加权分.
     * <ul>
     *   <li>用户点踩 +2</li>
     *   <li>用户点击"重新生成" +1</li>
     *   <li>用户提交工单 +3</li>
     * </ul>
     * ≥ {@code semantic-cache.feedback-threshold}(默认 5) 时自动置 DEGRADED.
     */
    private Integer feedbackScore;

    /**
     * 最后一次命中时间. 用于淘汰长期未命中的低价值缓存.
     */
    private LocalDateTime lastHitTime;

    /**
     * 过期时间 (写入时 = now() + ttl-hours).
     * Redis TTL 自然过期不通知 MySQL, 靠定时任务定期清理 expire_at < now() 的记录.
     */
    private LocalDateTime expireAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}