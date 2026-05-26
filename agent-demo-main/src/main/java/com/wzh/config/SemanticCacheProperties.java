package com.wzh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 语义缓存配置 (第3刀).
 *
 * <p>对应 application.yml 中 {@code semantic-cache.*} 配置块.</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "semantic-cache")
public class SemanticCacheProperties {

    /** 全局开关. false 时 CacheCheckNode 直接 fallthrough, FinalizeNode 不写缓存. */
    private boolean enabled = true;

    /**
     * L2 语义命中余弦相似度阈值.
     * 偏保守 (0.92), 牺牲命中率换零误判.
     * 后续可用评估 CI 跑阈值扫描 (0.85/0.88/0.90/0.92/0.95) 出"准确率-命中率"曲线.
     */
    private double similarityThreshold = 0.92;

    /** TTL (小时). Redis TTL + MySQL expire_at 写入时 = now() + ttlHours. */
    private int ttlHours = 24;

    /** feedback_score 达此值置 DEGRADED. */
    private int feedbackThreshold = 5;

    /** 用户点踩权重. */
    private int feedbackWeightDislike = 2;

    /** 用户点击"重新生成"权重. */
    private int feedbackWeightRegenerate = 1;

    /** 用户提交工单权重 (最强负反馈信号: 用户已经放弃这个答案了). */
    private int feedbackWeightTicket = 3;

    /** Milvus collection 名称. */
    private String milvusCollection = "semantic_cache_vectors";

    /** 命中时模拟流式推送的分块大小 (字符数). */
    private int replayChunkSize = 8;

    /** 命中时模拟流式推送的分块间隔 (毫秒). */
    private int replayChunkIntervalMs = 15;
}