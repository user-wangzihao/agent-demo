package com.wzh.service.intent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.config.IntentKeywordsConfig;
import com.wzh.enums.Intent;
import com.wzh.graph.support.GraphMetricsCollector;
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
 *   <li>few-shot 6 个示例, 覆盖 5 个核心意图 (含 admin_command 的 2 个边界样本), 引导 LLM 按 schema 输出.</li>
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
            你是一个意图分类器. 你的任务是把用户查询分类到以下 6 个意图之一, 并以 JSON 格式输出.
            
            意图定义:
            - how_to: 用户想知道"怎么做". 例: "怎么改色", "如何导入"
            - troubleshoot: 用户报告错误或异常. 例: "点击没反应", "导入失败"
            - feature_intro: 用户询问某功能的定义/用途. 例: "贴片栏是什么", "改色工具用来做什么"
            - chitchat: 社交性闲聊或与 AI 助手本身有关的对话.
              包括: 问候 / 感谢 / 道别 / 询问 AI 身份能力 / 关于天气时间等寒暄.
              例: "你好", "谢谢", "你是谁", "今天天气怎么样"
            - admin_command: 询问"知识库系统本身"的元数据/运营统计/管理操作.
              例: "还有哪些文档没学习", "本周问得最多的问题", "触发知识库重新学习", "用户满意度统计"
            - default: 用户在跟系统对话, 但问题不属于上述任何一类. 包括:
              1) 通用知识问答 (例: "地球到月球多远")
              2) 创作/写作/翻译任务请求 (例: "帮我写一首诗", "翻译这段话")
              3) 用户查询自己提交的工单状态 / 表达想转人工 (例: "我的工单 TK-xxx 状态", "转人工")
              4) 主观偏好或客观事实的二选一 / 价值判断 (例: "苹果和橘子哪个好吃")
              5) 无法归类的兜底
            
            ★ 边界判别规则 (最易混淆的点):
            - 询问"产品功能本身"的用法/故障/介绍 → how_to / troubleshoot / feature_intro
              即使提问者是管理员也一样 (例: 管理员问"BOM 工具怎么用" 仍是 how_to)
            - 询问"知识库系统本身"的状态/统计/管理 → admin_command
              (例: "看看现在还有多少知识没进库" 是 admin_command, 不是 feature_intro)
            - 用户问"自己的工单"状态 → default, 不是 admin_command
              (admin_command 是管理员问"系统层面的工单/数据统计", 不是用户问"我自己提的那张单")
            - chitchat 仅限社交性短语和关于 AI 自身的对话. 一切实质性问题
              (创作、翻译、二选一、知识问答) 都属于 default, 不是 chitchat.
            判断方法: 先看是否社交寒暄 (chitchat); 再看是否产品功能问题 (how_to/troubleshoot/feature_intro);
            再看是否系统管理问题 (admin_command); 都不是 → default.
            
            输出格式: 严格的 JSON 对象, 包含三个字段:
            - intent: 六个枚举值之一 (字符串)
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
            
            用户查询: 看看系统里现在有多少知识还没进库
            JSON 输出: {"intent": "admin_command", "confidence": 0.88, "reasoning": "询问知识库入库状态"}
            
            用户查询: 帮我统计一下最近用户反馈的情况
            JSON 输出: {"intent": "admin_command", "confidence": 0.85, "reasoning": "询问运营统计"}
            
            用户查询: 我的工单 TK-20240101001 处理得怎么样
            JSON 输出: {"intent": "default", "confidence": 0.9, "reasoning": "用户查询自有工单, 非系统管理"}
            
            用户查询: 周一开会还是周二开会比较好
            JSON 输出: {"intent": "default", "confidence": 0.85, "reasoning": "二选一判断, 非产品/系统/闲聊"}
            
            用户查询: 帮我写一封请假邮件
            JSON 输出: {"intent": "default", "confidence": 0.9, "reasoning": "创作任务请求, 非闲聊"}
            
            注意:
            1. 只输出 JSON 对象, 不要任何其他文字、Markdown 标记或注释
            2. 不要因为"必须选一个"就把非产品/非系统的问题硬塞进 chitchat —— 这类问题应该是 default.
               chitchat 仅限社交寒暄, default 才是"用户在和你对话但问题不在你专业范畴"的兜底.
            3. 区分 admin_command 与其他: 看用户问的对象是"产品功能/知识库系统"还是"用户自己的事".
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
                            TEMPERATURE, MAX_TOKENS, "json_object",
                            GraphMetricsCollector.MetricScene.INTENT_CLASSIFY))
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