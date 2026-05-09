package com.wzh.service.intent.impl;

import com.wzh.config.IntentKeywordsConfig;
import com.wzh.enums.Intent;
import com.wzh.model.intent.IntentClassificationResult;
import com.wzh.service.intent.IntentClassifier;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 关键词意图分类器 (规则匹配, 0ms 延迟).
 *
 * <p><b>注意</b>: 此分类器的 {@link #classify(String)} 方法可能返回 {@code null},
 * 表示"未确定"——需要由调用方 (通常是 {@code HybridIntentClassifier}) 降级到 LLM 处理.
 * 这是有意设计的, 用于区分"已确定为 DEFAULT"和"未确定".</p>
 *
 * <p><b>分类规则</b>:
 * <ul>
 *   <li>短 query (≤ 4 字) 优先匹配 chitchat → 直接定类 (问候/感谢类不会有歧义)</li>
 *   <li>遍历所有意图的关键词字典, 收集所有命中的意图</li>
 *   <li>命中单一意图 → 返回 confidence=1.0, source=KEYWORD</li>
 *   <li>命中多个意图 (冲突) → 返回 null, 交由 LLM 仲裁</li>
 *   <li>零命中 → 返回 null, 交由 LLM 兜底</li>
 * </ul>
 *
 * <p><b>设计取舍</b>:
 * 不强行在冲突时选一个意图 (如按命中数最多者), 因为关键词冲突往往代表语义模糊
 * (如"无法找到配置表"同时含 troubleshoot 和 how_to 信号), 强选会引入错误传播.
 * 把不确定性透传给 LLM 是更稳健的做法.
 *
 * @author wzh
 * @since 2026-05-08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordIntentClassifier implements IntentClassifier {

    /** 短 query 阈值 (字符数). 低于此值优先做 chitchat 检测. */
    private static final int SHORT_QUERY_THRESHOLD = 4;

    private final IntentKeywordsConfig config;

    /**
     * 关键词字典缓存: Intent → 已规范化 (lowercase + trim) 的关键词列表.
     * <p>预处理后的字典, 避免每次 classify 都重复 trim/lowercase.</p>
     */
    private Map<Intent, List<String>> normalizedKeywords;

    /**
     * 启动时把配置里的关键词预处理一次.
     * <p>使用 {@code @PostConstruct} 而非懒加载, 确保启动期暴露任何配置错误.</p>
     */
    @PostConstruct
    public void init() {
        EnumMap<Intent, List<String>> map = new EnumMap<>(Intent.class);
        for (Intent intent : Intent.values()) {
            if (intent == Intent.DEFAULT) {
                continue; // DEFAULT 没有关键词
            }
            List<String> raw = config.getKeywordsFor(intent);
            List<String> normalized = raw.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.trim().toLowerCase())
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            map.put(intent, normalized);
        }
        this.normalizedKeywords = Collections.unmodifiableMap(map);

        // 启动时打印关键词加载情况, 便于排查"yml 配错没生效"这类问题
        normalizedKeywords.forEach((intent, words) ->
                log.info("[Intent] 加载 {} 关键词 {} 个", intent.getCode(), words.size()));
    }

    /**
     * 关键词分类入口.
     *
     * @param query 用户原始查询 (调用方保证非 null/空)
     * @return 命中单一意图时返回结果; 冲突或未命中时返回 {@code null} 交由 LLM 处理
     */
    @Override
    public IntentClassificationResult classify(String query) {
        String normalized = query.trim().toLowerCase();

        // ---------- 1. 短 query 优先 chitchat 检测 ----------
        if (normalized.length() <= SHORT_QUERY_THRESHOLD) {
            String hitWord = firstHit(normalized, Intent.CHITCHAT);
            if (hitWord != null) {
                return hit(Intent.CHITCHAT, hitWord);
            }
            // 短 query 但不是 chitchat (如"原点位置")继续走通用流程
        }

        // ---------- 2. 收集所有命中的意图 ----------
        Map<Intent, String> hitsWithWord = new EnumMap<>(Intent.class);
        for (Map.Entry<Intent, List<String>> entry : normalizedKeywords.entrySet()) {
            String hitWord = firstHit(normalized, entry.getKey());
            if (hitWord != null) {
                hitsWithWord.put(entry.getKey(), hitWord);
            }
        }

        // ---------- 3. 三态判定 ----------
        if (hitsWithWord.isEmpty()) {
            log.debug("[Intent-Keyword] 未命中关键词, 交由 LLM 处理: {}", query);
            return null; // 未确定, 交给 LLM
        }

        if (hitsWithWord.size() == 1) {
            Map.Entry<Intent, String> only = hitsWithWord.entrySet().iterator().next();
            return hit(only.getKey(), only.getValue());
        }

        // 多类冲突 → 不强选, 交给 LLM
        log.debug("[Intent-Keyword] 多意图冲突 {}, 交由 LLM 仲裁: {}",
                hitsWithWord.keySet(), query);
        return null;
    }

    /**
     * 在指定意图的关键词字典中查找第一个命中的词.
     *
     * @param normalizedQuery 已规范化的 query
     * @param intent          目标意图
     * @return 命中的关键词原文 (用于日志/reasoning); 未命中返回 null
     */
    private String firstHit(String normalizedQuery, Intent intent) {
        List<String> keywords = normalizedKeywords.getOrDefault(intent, List.of());
        for (String kw : keywords) {
            if (normalizedQuery.contains(kw)) {
                return kw;
            }
        }
        return null;
    }

    /**
     * 构造命中结果. confidence 固定为 1.0, source 固定为 KEYWORD.
     */
    private IntentClassificationResult hit(Intent intent, String hitWord) {
        log.debug("[Intent-Keyword] 命中 {} (关键词: {})", intent.getCode(), hitWord);
        return IntentClassificationResult.builder()
                .intent(intent)
                .confidence(1.0)
                .reasoning("关键词命中: " + hitWord)
                .source(IntentClassificationResult.Source.KEYWORD)
                .build();
    }
}