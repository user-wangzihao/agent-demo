package com.wzh.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

/**
 * 用户查询意图分类.
 *
 * <p>4 + 1 分类设计:
 * <ul>
 *   <li>{@link #HOW_TO}        - 操作指引类: "怎么做"、"如何"、"步骤"</li>
 *   <li>{@link #TROUBLESHOOT}  - 故障排查类: "报错"、"无法"、"失败"</li>
 *   <li>{@link #FEATURE_INTRO} - 功能介绍类: "是什么"、"有什么用"</li>
 *   <li>{@link #CHITCHAT}      - 闲聊类: "你好"、"谢谢"</li>
 *   <li>{@link #DEFAULT}       - 兜底类: 上述都不匹配 / LLM 失败 / 置信度低</li>
 * </ul>
 *
 * <p>下游使用方:
 * <ul>
 *   <li>{@code AgentService} - chitchat 短路, 其他类型走 RAG 分支</li>
 *   <li>{@code chunk type boost} - 不同意图加权不同的 chunk_type</li>
 *   <li>{@code SystemPromptBuilder} - 不同意图使用不同的回答风格模板</li>
 * </ul>
 *
 * <p><b>Jackson 序列化约定</b>:
 * <ul>
 *   <li>反序列化: {@link #fromJson(String)} 标 {@code @JsonCreator}, Jackson 会自动调用</li>
 *   <li>序列化: {@link #getCode()} 标 {@code @JsonValue}, Jackson 输出 code 字符串而非枚举名</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-08
 */
@Getter
@Slf4j
public enum Intent {

    /** 操作指引: 用户想知道如何完成某个操作. 加权 operation_guide chunk. */
    HOW_TO("how_to", "操作指引", "operation_guide"),

    /** 故障排查: 用户报告错误或无法完成操作. 加权 error_solution chunk. */
    TROUBLESHOOT("troubleshoot", "故障排查", "error_solution"),

    /** 功能介绍: 用户询问某功能本身的定义/用途. 加权 feature_intro chunk. */
    FEATURE_INTRO("feature_intro", "功能介绍", "feature_intro"),

    /** 闲聊: 与产品功能无关的对话. 短路, 不走 RAG. */
    CHITCHAT("chitchat", "闲聊", null),

    /** 兜底: 未识别 / LLM 失败 / 置信度低. 走原有未分支流程. */
    DEFAULT("default", "默认", null);

    /**
     * 意图代码 (用于 LLM 输出 / 日志 / 评估).
     * <p>{@code @JsonValue} 让 Jackson 序列化时输出此字段而非枚举名 (HOW_TO).</p>
     */
    @JsonValue
    private final String code;

    /** 中文显示名 (用于日志和面板展示) */
    private final String displayName;

    /**
     * 该意图对应需要在检索阶段加权的 chunk_type.
     * <p>{@code null} 表示该意图不需要 chunk type boost (chitchat / default).</p>
     */
    private final String boostChunkType;

    Intent(String code, String displayName, String boostChunkType) {
        this.code = code;
        this.displayName = displayName;
        this.boostChunkType = boostChunkType;
    }

    /**
     * Jackson 反序列化入口. 收到 LLM 输出的 code 字符串 (如 "how_to") 时,
     * Jackson 自动调用此方法解析为枚举值.
     *
     * <p><b>关键约束</b>: 必须用 {@code @JsonCreator} 标注, 否则 Jackson 默认按
     * 枚举名 (HOW_TO/CHITCHAT) 反序列化, 收到小写 code 会抛
     * {@code Cannot deserialize value of type Intent from String "how_to"}.</p>
     *
     * <p>未知 code 容错降级为 {@link #DEFAULT}, 不抛异常, 让 LlmIntentClassifier 后续
     * 通过 confidence 阈值或其他逻辑做更精细的处理.</p>
     *
     * @param code Jackson 传入的 code 字符串
     * @return 对应的 Intent, 解析失败返回 DEFAULT
     */
    @JsonCreator
    public static Intent fromJson(String code) {
        return fromCodeOrDefault(code);
    }

    /**
     * 根据 code 解析 Intent. 解析失败返回 {@link #DEFAULT}.
     *
     * @param code 意图代码 (大小写不敏感, 可为 null)
     * @return 对应的 Intent, 解析失败返回 DEFAULT
     */
    public static Intent fromCodeOrDefault(String code) {
        return parseCode(code).orElse(DEFAULT);
    }

    /**
     * 严格解析: 解析失败返回 empty. 用于评估场景区分"未识别"和"识别为 default".
     *
     * @param code 意图代码 (大小写不敏感, 可为 null)
     * @return Optional 包装的 Intent
     */
    public static Optional<Intent> parseCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(i -> i.code.equals(normalized))
                .findFirst();
    }

    /**
     * 是否需要在检索阶段做 chunk_type 加权.
     *
     * @return true 表示该意图有对应的 boostChunkType
     */
    public boolean needsBoost() {
        return boostChunkType != null;
    }

    /**
     * 是否为闲聊短路意图 (跳过 RAG 流程).
     */
    public boolean isShortCircuit() {
        log.info("[INTENT] isShortCircuit={}", this == CHITCHAT);
        log.info("[INTENT] isShortCircuit={}", this.code);
        log.info("[INTENT] isShortCircuit={}", this.displayName);
        log.info("[INTENT] isShortCircuit={}", this.boostChunkType);
        return this == CHITCHAT;
    }
}