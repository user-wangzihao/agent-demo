package com.wzh.service;

import com.wzh.agentdemo.common.entity.FeatureDocument;
import com.wzh.config.ProductionRetrieveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用 qwen-turbo 从用户 query 提取 feature_name 候选.
 *
 * <p><b>设计要点</b>:
 * <ul>
 *   <li>把数据库现有的 feature_name 列表注入 system prompt, 限制 LLM 只能从中选</li>
 *   <li>明确返回 NONE 表示无相关 feature, 避免 LLM 强行猜测</li>
 *   <li>失败一律返回 null, 不抛异常 — 上游会走 fallback 链路</li>
 *   <li>提取结果还需经 {@link FeatureNameMatcher} 二次确认 (LLM 偶尔会输出带后缀的版本)</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureExtractService {

    private final DashScopeService dashScopeService;
    private final FeatureDocumentService featureDocumentService;
    private final ProductionRetrieveProperties props;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个功能名识别助手。系统中已有以下功能点(共 %d 个):
            %s
            
            用户会向你提问,你的任务: 从用户的问题中识别出最相关的一个功能点名称。
            
            规则:
            1. 只能从上述功能点列表中选择,不能创造新的名称
            2. 如果用户问题明显与某个功能相关 → 返回该功能的标准名称(原样)
            3. 如果用户问题不涉及任何具体功能 (如打招呼、抽象问题、跨功能咨询) → 返回字符串: NONE
            4. 如果用户问题涉及多个功能 → 返回最主要的那一个
            5. 直接返回功能名称或 NONE,不要任何解释、不要标点、不要引号
            
            示例:
            用户:如何使用赋属性这个功能?
            输出:赋属性工具
            
            用户:你好,在吗?
            输出:NONE
            """;

    /**
     * 从用户 query 提取 feature_name 候选.
     *
     * @param query 用户原始 query (或 enhancedMessage)
     * @return 候选 feature_name; 未识别 / 失败返回 null
     */
    public String extract(String query) {
        try {
            List<String> dbNames = featureDocumentService.list().stream()
                    .map(FeatureDocument::getFeatureName)
                    .filter(n -> n != null && !n.trim().isEmpty())
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toList());

            if (dbNames.isEmpty()) {
                log.warn("[FEATURE-EXTRACT] 数据库无 feature_name, 跳过提取");
                return null;
            }

            String featureList = dbNames.stream()
                    .map(n -> "- " + n)
                    .collect(Collectors.joining("\n"));
            String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, dbNames.size(), featureList);

            String result = dashScopeService.chatOnce(
                    "qwen-turbo",
                    systemPrompt,
                    "用户:" + query + "\n输出:",
                    props.getTemperature(),
                    props.getMaxTokens(),
                    null
            );

            if (result == null) return null;
            // 防御性清理: LLM 偶尔加引号、句号等
            String cleaned = result.trim().replaceAll("[\"'。.,，:：]", "").trim();
            if (cleaned.isEmpty() || "NONE".equalsIgnoreCase(cleaned)) {
                log.info("[FEATURE-EXTRACT] LLM 判定无相关 feature query={}", truncate(query, 40));
                return null;
            }
            log.info("[FEATURE-EXTRACT] 提取候选={} query={}", cleaned, truncate(query, 40));
            return cleaned;

        } catch (Exception e) {
            log.warn("[FEATURE-EXTRACT] 提取异常 query={} err={}",
                    truncate(query, 40), e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}