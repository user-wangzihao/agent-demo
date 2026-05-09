package com.wzh.config;

import com.wzh.enums.Intent;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * 意图分类相关配置.
 *
 * <p>对应 {@code application.yml} 中的 {@code intent} 配置块:
 * <pre>
 * intent:
 *   classifier:
 *     llm-fallback-enabled: true       # LLM 兜底开关
 *     llm-model: qwen-turbo             # LLM 模型名
 *     llm-timeout-ms: 3000              # LLM 调用超时
 *     confidence-threshold: 0.6         # 低于此值降级到 DEFAULT
 *   keywords:
 *     how_to:
 *       - 怎么
 *       - 如何
 *       - 步骤
 *     troubleshoot:
 *       - 报错
 *       - 失败
 *       - 无法
 *     feature_intro:
 *       - 是什么
 *       - 有什么用
 *     chitchat:
 *       - 你好
 *       - 谢谢
 * </pre>
 *
 * <p>关键词配置外置原因:
 * <ul>
 *   <li>关键词需要根据真实流量调优 (面试讲点: 数据驱动)</li>
 *   <li>避免硬编码, 改关键词不需要重新打包</li>
 *   <li>不同租户/场景未来可能需要不同关键词集</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-08
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "intent")
public class IntentKeywordsConfig {

    /** 分类器行为相关配置 */
    private Classifier classifier = new Classifier();

    /**
     * 关键词字典: code → 关键词列表.
     * <p>使用 String 而非 Intent 作为 key, 因为 yml 反序列化对枚举 key 支持不稳定.</p>
     */
    private Map<String, List<String>> keywords = new HashMap<>();

    /**
     * 获取指定意图的关键词列表 (永不为 null).
     */
    public List<String> getKeywordsFor(Intent intent) {
        return keywords.getOrDefault(intent.getCode(), Collections.emptyList());
    }

    /**
     * 分类器行为配置.
     */
    @Data
    public static class Classifier {

        /** 是否启用 LLM 兜底 (关键词未命中时调 LLM). 关闭后未命中直接 DEFAULT, 用于成本敏感场景. */
        private boolean llmFallbackEnabled = true;

        /** LLM 兜底使用的模型 */
        private String llmModel = "qwen-turbo";

        /** LLM 调用超时 (毫秒) */
        private int llmTimeoutMs = 3000;

        /**
         * 置信度阈值. LLM 返回 confidence 低于此值时降级为 DEFAULT.
         * <p>取 0.6 是经验值: 高于 0.6 视为可信, 低于则不冒险走分支处理.</p>
         */
        private double confidenceThreshold = 0.6;
    }
}