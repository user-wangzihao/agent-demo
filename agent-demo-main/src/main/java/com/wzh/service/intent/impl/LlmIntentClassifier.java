package com.wzh.service.intent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.config.IntentKeywordsConfig;
import com.wzh.enums.Intent;
import com.wzh.model.intent.IntentClassificationResult;
import com.wzh.service.DashScopeService;
import com.wzh.service.intent.IntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 意图分类器 (qwen-turbo + DashScope JSON Mode + few-shot).
 *
 * <p><b>设计要点</b>:
 * <ol>
 *   <li>开启 DashScope JSON Mode ({@code response_format: {"type":"json_object"}}),
 *       SDK ≥ 2.18.4 支持. prompt 里必须包含 "JSON" 关键词.</li>
 *   <li>few-shot 4 个示例, 覆盖 4 个核心意图, 引导 LLM 按 schema 输出.</li>
 *   <li>{@code temperature=0.1} 保证近确定性输出, 降低分类抖动.</li>
 *   <li>超时控制: 通过 {@link CompletableFuture#orTimeout} 强制 yml 配置的超时上限,
 *       避免阻塞用户对话. 超时/异常一律降级为 FALLBACK 兜底.</li>
 *   <li>置信度阈值: LLM 返回 result 但 confidence &lt; 阈值 → 降级为 FALLBACK,
 *       避免不可信分类影响下游分支.</li>
 *   <li>DEFAULT 降级: LLM 输出 default 或未知意图 (经 @JsonCreator 兜底为 DEFAULT) → 降级为 FALLBACK,
 *       避免 LLM 把"分不清"误标为高置信度的 DEFAULT 分类.</li>
 * </ol>
 *
 * <p><b>失败处理 (与 IntentClassifier 接口契约一致)</b>:
 * 不抛异常, 任何失败 (网络异常 / 超时 / JSON 解析失败 / 置信度不足 / LLM 输出 DEFAULT)
 * 都返回 {@link IntentClassificationResult#defaultResult(String)}.
 *
 * @author wzh
 * @since 2026-05-08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmIntentClassifier implements IntentClassifier {

    private final DashScopeService dashScopeService;
    private final ObjectMapper objectMapper;
    private final IntentKeywordsConfig config;

    /**
     * System Prompt: 角色定义 + 输出 schema + few-shot 示例.
     */
    private static final String SYSTEM_PROMPT = """
            你是一个意图分类器. 你的任务是把用户查询分类到以下 5 个意图之一, 并以 JSON 格式输出.
            
            意图定义:
            - how_to: 用户想知道"怎么做". 例: "怎么改色", "如何导入"
            - troubleshoot: 用户报告错误或异常. 例: "点击没反应", "导入失败"
            - feature_intro: 用户询问某功能的定义/用途. 例: "贴片栏是什么", "改色工具用来做什么"
            - chitchat: 与产品功能无关的对话. 例: "你好", "谢谢"
            - default: 上述都不匹配, 或者意图不明确
            
            输出格式: 严格的 JSON 对象, 包含三个字段:
            - intent: 五个枚举值之一 (字符串)
            - confidence: 置信度 0.0~1.0 (数字, 不确定时给低分)
            - reasoning: 一句话简短理由 (字符串, 不超过 30 字)
            
            示例:
            
            用户查询: 贴片栏工具怎么用
            JSON 输出: {"intent": "how_to", "confidence": 0.95, "reasoning": "询问操作方式"}
            
            用户查询: 应用更新点击没反应
            JSON 输出: {"intent": "troubleshoot", "confidence": 0.92, "reasoning": "报告功能异常"}
            
            用户查询: 快速改色工具是干什么的
            JSON 输出: {"intent": "feature_intro", "confidence": 0.9, "reasoning": "询问功能用途"}
            
            用户查询: 你好啊
            JSON 输出: {"intent": "chitchat", "confidence": 1.0, "reasoning": "问候语"}
            
            注意:
            1. 只输出 JSON 对象, 不要任何其他文字、Markdown 标记或注释
            2. 意图不明确时, 给低 confidence 并选 default
            """;

    /** LLM 调用模型名 (固定 qwen-turbo, 性价比最优). */
    private static final String MODEL = "qwen-turbo";

    /** LLM temperature: 低值 (0.1) 保证近确定性输出. */
    private static final float TEMPERATURE = 0.1f;

    /** maxTokens: JSON 输出很短, 200 token 足够. */
    private static final int MAX_TOKENS = 200;

    @Override
    public IntentClassificationResult classify(String query) {
        long start = System.currentTimeMillis();
        int timeoutMs = config.getClassifier().getLlmTimeoutMs();

        try {
            String json = CompletableFuture
                    .supplyAsync(() -> dashScopeService.chatOnce(
                            MODEL, SYSTEM_PROMPT, "用户查询: " + query + "\nJSON 输出:",
                            TEMPERATURE, MAX_TOKENS, "json_object"))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .join();

            return parseAndValidate(json, query, System.currentTimeMillis() - start);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                log.warn("[Intent-LLM] 超时降级 query='{}' latency={}ms timeout={}ms",
                        query, latency, timeoutMs);
                return IntentClassificationResult.defaultResult("LLM 调用超时");
            }
            log.warn("[Intent-LLM] 调用失败降级 query='{}' latency={}ms err={}",
                    query, latency, cause.getMessage());
            return IntentClassificationResult.defaultResult("LLM 调用异常: " + cause.getMessage());
        }
    }

    /**
     * 解析 LLM 返回的 JSON, 应用置信度阈值, 标记 source.
     */
    private IntentClassificationResult parseAndValidate(String json, String query, long latency) {
        if (json == null || json.isBlank()) {
            log.warn("[Intent-LLM] LLM 返回空 query='{}' latency={}ms", query, latency);
            return IntentClassificationResult.defaultResult("LLM 返回空内容");
        }

        IntentClassificationResult result;
        try {
            String cleaned = stripMarkdownFence(json);
            result = objectMapper.readValue(cleaned, IntentClassificationResult.class);
        } catch (JsonProcessingException e) {
            log.warn("[Intent-LLM] JSON 解析失败 query='{}' raw='{}' err={}",
                    query, json, e.getMessage());
            return IntentClassificationResult.defaultResult("JSON 解析失败");
        }

        // intent 字段缺失或反序列化为 null → 降级 (理论上不会发生, @JsonCreator 已兜底为 DEFAULT)
        if (result.getIntent() == null) {
            log.warn("[Intent-LLM] 解析后 intent 为空 query='{}' raw='{}'", query, json);
            return IntentClassificationResult.defaultResult("intent 字段无效");
        }

        // LLM 输出 DEFAULT (包括: 模型主动选 default, 或返回未知意图被 @JsonCreator 兜底为 DEFAULT)
        // → 视为 "LLM 也分不清", 降级 FALLBACK, 让下游走原有未分支流程
        // 注意: 这一步必须在置信度判断之前, 因为即使 confidence 高, DEFAULT 也不该带 LLM source
        if (result.getIntent() == Intent.DEFAULT) {
            log.info("[Intent-LLM] LLM 输出 DEFAULT 降级 query='{}' raw='{}' latency={}ms",
                    query, json, latency);
            return IntentClassificationResult.defaultResult("LLM 输出无效或不明确意图");
        }

        // 置信度阈值校验
        double threshold = config.getClassifier().getConfidenceThreshold();
        if (result.getConfidence() < threshold) {
            log.info("[Intent-LLM] 置信度不足降级 query='{}' intent={} conf={} threshold={} latency={}ms",
                    query, result.getIntent().getCode(), result.getConfidence(), threshold, latency);
            return IntentClassificationResult.defaultResult(
                    "置信度 " + result.getConfidence() + " < " + threshold);
        }

        // 标记来源
        result.setSource(IntentClassificationResult.Source.LLM);
        log.info("[Intent-LLM] 命中 query='{}' intent={} conf={} latency={}ms reason={}",
                query, result.getIntent().getCode(), result.getConfidence(), latency,
                result.getReasoning());
        return result;
    }

    /**
     * 剥离 Markdown 代码块包裹.
     */
    private String stripMarkdownFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }
}