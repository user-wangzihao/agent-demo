package com.wzh.agentdemo.common.entity;

/**
 * 语义缓存状态常量 (对应 semantic_cache.status 字段).
 *
 * <p>用字符串常量而非 enum, 与 {@code FaqCandidate.reviewStatus} 风格保持一致, 规避
 * Spring AI Alibaba Graph 在 state/conditionalEdge 上下文中对枚举的序列化踩坑.</p>
 */
public final class CacheStatus {

    /** 正常可命中. */
    public static final String ACTIVE = "ACTIVE";

    /** 负反馈超阈值, 命中时跳过缓存重新生成. 新回答写回时恢复 ACTIVE + 清零 feedback_score. */
    public static final String DEGRADED = "DEGRADED";

    /** 管理员主动失效 (文档重学 / FAQ 操作), 永不命中. */
    public static final String INVALID = "INVALID";

    private CacheStatus() {}
}