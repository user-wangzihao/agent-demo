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
 * <p>5 + 1 分类设计 (业务意图 + 管理员指令 + 兜底):
 * <ul>
 *   <li>{@link #HOW_TO}         - 操作指引类: "怎么做"、"如何"、"步骤"</li>
 *   <li>{@link #TROUBLESHOOT}   - 故障排查类: "报错"、"无法"、"失败"</li>
 *   <li>{@link #FEATURE_INTRO}  - 功能介绍类: "是什么"、"有什么用"</li>
 *   <li>{@link #CHITCHAT}       - 闲聊类: "你好"、"谢谢"; 短路到 chitchat_answer</li>
 *   <li>{@link #ADMIN_COMMAND}  - 管理员指令类: 询问知识库元数据/运营统计/管理操作;
 *                                短路到 admin_agent (需 userRole=admin, 否则降级)</li>
 *   <li>{@link #DEFAULT}        - 兜底类: 上述都不匹配 / LLM 失败 / 置信度低</li>
 * </ul>
 *
 * <p><b>边界划分 (HOW_TO vs ADMIN_COMMAND)</b>: 询问对象决定意图归属.
 * <ul>
 *   <li>询问<b>产品功能本身</b>的用法/故障/介绍 → HOW_TO / TROUBLESHOOT / FEATURE_INTRO,
 *       即使提问者是管理员 (例: "BOM 工具怎么用")</li>
 *   <li>询问<b>知识库系统本身</b>的元数据/统计/管理操作 → ADMIN_COMMAND
 *       (例: "还有多少文档没学习", "本周问得最多的问题")</li>
 * </ul>
 *
 * <p>下游使用方:
 * <ul>
 *   <li>{@code MainGraphConfig.routeAfterIntent} - chitchat / admin_command 短路, 其他类型进 RAG 分支</li>
 *   <li>{@code chunk type boost} - 不同意图加权不同的 chunk_type (CHITCHAT/ADMIN_COMMAND/DEFAULT 不加权)</li>
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
public enum Intent {

    /** 操作指引: 用户想知道如何完成某个操作. 加权 operation_guide chunk. */
    HOW_TO("how_to", "操作指引", "operation_guide"),

    /** 故障排查: 用户报告错误或无法完成操作. 加权 error_solution chunk. */
    TROUBLESHOOT("troubleshoot", "故障排查", "error_solution"),

    /** 功能介绍: 用户询问某功能本身的定义/用途. 加权 feature_intro chunk. */
    FEATURE_INTRO("feature_intro", "功能介绍", "feature_intro"),

    /** 闲聊: 与产品功能无关的对话. 短路, 不走 RAG. */
    CHITCHAT("chitchat", "闲聊", null),

    /**
     * 管理员指令: 询问知识库系统本身的元数据/运营统计/管理操作.
     *
     * <p><b>典型例子</b>: "还有哪些文档没学习", "本周问得最多的问题",
     * "触发知识库重新学习", "统计用户满意度".</p>
     *
     * <p><b>路由行为</b>: 在 {@code routeAfterIntent} 阶段短路到 admin_agent 节点,
     * 不走 RAG 链路 (不调用 feature_resolve / doc_retrieve / faq_retrieve / merger),
     * 因为这类问题靠管理员工具调用回答, 与知识库 chunk 无关.</p>
     *
     * <p><b>userRole 校验</b>: 在路由层 ({@code routeAfterIntent}) 而非分类层做.
     * 非管理员用户即使被分类为 ADMIN_COMMAND, 也降级到 knowledge_answer 而非拒绝.
     * 真正的权限边界由 ChatClient 工具集隔离 (Batch 2) 物理保证.</p>
     *
     * <p><b>不加权</b>: boostChunkType=null, 因为不走 RAG.</p>
     */
    ADMIN_COMMAND("admin_command", "管理员指令", null),

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
     * 是否为闲聊短路意图 (跳过 RAG 流程, 直接到 chitchat_answer).
     *
     * <p><b>注意</b>: 仅 CHITCHAT 返回 true. ADMIN_COMMAND 虽然也跳过 RAG,
     * 但短路目标是 admin_agent 而非 chitchat_answer, 路由层独立判定,
     * 不复用此方法 (否则 admin 流量会被错误路由到 chitchat_answer).</p>
     */
    public boolean isShortCircuit() {
        return this == CHITCHAT;
    }

    /**
     * 是否为管理员指令意图 (跳过 RAG 流程, 短路到 admin_agent).
     *
     * <p>与 {@link #isShortCircuit()} 配合, 在 {@code routeAfterIntent} 实现三分流:
     * chitchat / admin_command / 其他 (进 RAG 链路).</p>
     *
     * <p>路由层还需结合 {@code userRole == admin} 才真正进 admin_agent,
     * 此方法只表达"意图本身是不是管理员指令", 不含权限判断.</p>
     */
    public boolean isAdminCommand() {
        return this == ADMIN_COMMAND;
    }
}