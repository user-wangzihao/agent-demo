package com.wzh.service.intent.impl;

import com.wzh.config.IntentKeywordsConfig;
import com.wzh.model.intent.IntentClassificationResult;
import com.wzh.service.intent.IntentClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 混合策略意图分类器 (业务层最终使用的 Bean).
 *
 * <p><b>协调流程</b>:
 * <pre>
 * 1. 调 KeywordIntentClassifier
 *    └─ 命中 (非 null)  → 直接返回 (~60% 流量, 0ms)
 *    └─ 未命中或冲突 (null) → 进入步骤 2
 *
 * 2. 检查 yml 是否启用 LLM 兜底
 *    └─ 关闭 → 返回 DEFAULT (FALLBACK 来源)
 *    └─ 开启 → 进入步骤 3
 *
 * 3. 调 LlmIntentClassifier
 *    └─ 内部已处理超时/失败/置信度阈值, 永不抛异常
 *    └─ 返回结果直接透传
 * </pre>
 *
 * <p><b>预期分布</b>:
 * <ul>
 *   <li>~60% 流量: 关键词命中 (0ms)</li>
 *   <li>~35% 流量: LLM 兜底 (~500ms)</li>
 *   <li>~5% 流量: FALLBACK 兜底 (LLM 失败/超时/置信度低)</li>
 * </ul>
 *
 * <p>{@code @Primary} 注解确保 {@code @Autowired IntentClassifier} 时
 * Spring 注入此 Bean, 而不是 KeywordIntentClassifier 或 LlmIntentClassifier.</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
@Slf4j
@Primary
@Component("intentClassifier")
public class HybridIntentClassifier implements IntentClassifier {

    private final KeywordIntentClassifier keywordClassifier;
    private final LlmIntentClassifier llmClassifier;
    private final IntentKeywordsConfig config;

    /**
     * 构造器注入. 显式指定具体实现类型而非接口, 避免 Spring 注入到自身造成循环依赖
     * (因为本类也实现了 IntentClassifier).
     */
    public HybridIntentClassifier(KeywordIntentClassifier keywordClassifier,
                                  LlmIntentClassifier llmClassifier,
                                  IntentKeywordsConfig config) {
        this.keywordClassifier = keywordClassifier;
        this.llmClassifier = llmClassifier;
        this.config = config;
    }

    @Override
    public IntentClassificationResult classify(String query) {
        if (query == null || query.isBlank()) {
            // 空 query 按 DEFAULT 处理, 不应该到这里, 但兜底
            return IntentClassificationResult.defaultResult("空查询");
        }

        // ---------- Step 1: 关键词分类 ----------
        IntentClassificationResult keywordResult = keywordClassifier.classify(query);
        if (keywordResult != null) {
            // 命中, 直接返回
            return keywordResult;
        }

        // ---------- Step 2: LLM 兜底开关检查 ----------
        if (!config.getClassifier().isLlmFallbackEnabled()) {
            log.debug("[Intent-Hybrid] LLM 兜底已关闭, 关键词未命中 → DEFAULT query='{}'", query);
            return IntentClassificationResult.defaultResult("LLM 兜底已禁用");
        }

        // ---------- Step 3: 调 LLM 兜底 ----------
        // LlmIntentClassifier 内部已处理所有异常, 永不抛异常
        return llmClassifier.classify(query);
    }
}