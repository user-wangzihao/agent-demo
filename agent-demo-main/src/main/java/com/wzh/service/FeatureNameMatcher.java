package com.wzh.service;

import com.wzh.agentdemo.common.entity.FeatureDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Feature 名称匹配工具.
 *
 * <p><b>三层匹配策略</b>:
 * <ol>
 *   <li>精确匹配 (trim 后字符串相等)</li>
 *   <li>双向包含 (候选 ⊃ 数据库 OR 数据库 ⊃ 候选) — 容忍"赋属性"vs"赋属性工具"等细微差距</li>
 *   <li>未命中 → 返回 null, 由调用方降级</li>
 * </ol></p>
 *
 * <p><b>不做的事</b>: 编辑距离 / 模糊相似度 — 容易误命中
 * (如"刻字"和"涂色"的字符相似度可能凑巧达标), 宁缺毋滥.</p>
 *
 * <p><b>性能</b>: 当前每次匹配都查数据库 ({@code featureDocumentService.list()}),
 * 后续接 Redis 缓存优化.</p>
 *
 * @author wzh
 * @since 2026-05-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureNameMatcher {

    private final FeatureDocumentService featureDocumentService;

    /**
     * 把候选 feature_name 匹配到数据库中的标准名称.
     *
     * @param candidate 候选名称 (前端传入或 LLM 提取)
     * @return 数据库标准名; 未命中返回 null
     */
    public String match(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return null;
        }
        String trimmed = candidate.trim();

        List<String> dbNames = featureDocumentService.list().stream()
                .map(FeatureDocument::getFeatureName)
                .filter(n -> n != null && !n.trim().isEmpty())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());

        if (dbNames.isEmpty()) {
            log.warn("[FEATURE-MATCH] 数据库无 feature_name");
            return null;
        }

        // Step 1: 精确匹配
        for (String name : dbNames) {
            if (name.equals(trimmed)) {
                log.debug("[FEATURE-MATCH] 精确命中 candidate={} → {}", trimmed, name);
                return name;
            }
        }
        // Step 2: 双向包含
        for (String name : dbNames) {
            if (trimmed.contains(name) || name.contains(trimmed)) {
                log.info("[FEATURE-MATCH] 包含命中 candidate={} → {}", trimmed, name);
                return name;
            }
        }
        log.info("[FEATURE-MATCH] 未命中 candidate={}", trimmed);
        return null;
    }
}