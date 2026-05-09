package com.wzh.model.intent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wzh.enums.Intent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图分类结果.
 *
 * <p>同时承担两个角色:
 * <ol>
 *   <li>LLM 结构化输出的反序列化目标 (Jackson 解析 qwen-turbo 返回的 JSON)</li>
 *   <li>分类器层之间和分类器→AgentService 的传递载体</li>
 * </ol>
 *
 * <p>字段约定:
 * <ul>
 *   <li>{@code intent}      - 必填, 解析失败时由调用方降级为 {@link Intent#DEFAULT}</li>
 *   <li>{@code confidence}  - 0.0~1.0; 关键词命中固定为 1.0; LLM 返回时由模型给出</li>
 *   <li>{@code reasoning}   - 可选; 用于日志/可观测性, 后续 Grafana 面板会展示</li>
 *   <li>{@code source}      - 标识来源 (KEYWORD/LLM/FALLBACK), 用于评估和监控</li>
 * </ul>
 *
 * <p>使用 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 是为了兼容 LLM 偶尔
 * 多输出字段的情况 (即使 prompt 严格约束, LLM 仍可能加 explanation/extra 字段).</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntentClassificationResult {

    /**
     * 识别出的意图.
     * <p>JSON 反序列化时通过 {@code intent} code 字符串解析,
     * 由 {@link Intent#fromCodeOrDefault(String)} 兜底.</p>
     */
    @JsonProperty("intent")
    private Intent intent;

    /**
     * 置信度 (0.0 ~ 1.0).
     * <p>关键词分类器固定为 1.0; LLM 分类器由模型给出.
     * 调用方可基于阈值降级 (如 confidence &lt; 0.6 → DEFAULT).</p>
     */
    @JsonProperty("confidence")
    private double confidence;

    /**
     * 分类理由 (可选, 用于 debug 和监控).
     * <p>关键词分类器填命中的关键词; LLM 分类器由模型给出简短理由.</p>
     */
    @JsonProperty("reasoning")
    private String reasoning;

    /**
     * 分类来源.
     * <p>不参与 JSON 反序列化 (LLM 不输出此字段, 由分类器自己赋值).</p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Source source;

    /**
     * 构造一个 DEFAULT 兜底结果.
     */
    public static IntentClassificationResult defaultResult(String reason) {
        return IntentClassificationResult.builder()
                .intent(Intent.DEFAULT)
                .confidence(0.0)
                .reasoning(reason)
                .source(Source.FALLBACK)
                .build();
    }

    /**
     * 分类来源标识.
     */
    public enum Source {
        /** 关键词规则命中 (0ms 延迟) */
        KEYWORD,
        /** LLM 兜底分类 (~500ms 延迟) */
        LLM,
        /** 异常降级 (LLM 失败 / 置信度低) */
        FALLBACK
    }
}