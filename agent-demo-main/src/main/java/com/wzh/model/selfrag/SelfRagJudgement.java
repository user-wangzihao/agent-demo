package com.wzh.model.selfrag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Self-RAG 单版答案的三维诊断结果 (Self-RAG Batch 1).
 *
 * <p>承担两个角色:
 * <ol>
 *   <li>judge LLM 结构化输出的反序列化目标 (Jackson 解析 qwen-plus 返回的 JSON)</li>
 *   <li>knowledge_answer 节点据此决定 PASS / 触发第2版生成的判定载体</li>
 * </ol>
 *
 * <p><b>三个维度</b> (借鉴 Self-RAG 论文的 reflection token 思想, 简化为布尔判别):
 * <ul>
 *   <li>{@code grounded}  - 答案是否真正基于检索到的 context (反幻觉)。
 *       false = 答案脱离 context 自由发挥 → 说明召回的 context 本身不对路, 应触发<b>重检索</b>。</li>
 *   <li>{@code relevant}  - 答案是否答到了用户真正问的点 (反答非所问)。
 *       false = 召回可能是对的但生成跑题 → 应<b>换 prompt 重生成</b>。</li>
 *   <li>{@code complete}  - (仅操作/排查类) 步骤是否完整可操作。
 *       false = how_to 类答案缺步骤 → <b>换 prompt 重生成</b>。事实类意图此维恒 true。</li>
 * </ul>
 *
 * <p><b>verdict 由节点侧规则映射</b> (不让 LLM 自由发挥 verdict, LLM 只输出三个布尔 + reason):
 * <ul>
 *   <li>三维全 true → PASS (收敛优化: 直接采纳第1版, 不生成第2版)</li>
 *   <li>grounded=false → RETRY_RETRIEVE (context 不对路, 重检索后生成第2版)</li>
 *   <li>grounded=true 但 relevant/complete 有 false → RETRY_GEN (同 context 换 prompt 生成第2版)</li>
 * </ul>
 * grounded 是关键路由信号: 它区分了"召回层问题"和"生成层问题", 决定第2版是重检索还是重生成。</p>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}: 容忍 LLM 偶尔多输出字段。</p>
 *
 * @author wzh
 * @since 2026-05-28 (Self-RAG Batch 1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SelfRagJudgement {

    /** 答案是否扎根于检索 context (反幻觉). LLM 输出。 */
    @JsonProperty("grounded")
    private boolean grounded;

    /** 答案是否切中用户问题 (反答非所问). LLM 输出。 */
    @JsonProperty("relevant")
    private boolean relevant;

    /**
     * 答案 (操作/排查类) 步骤是否完整. LLM 输出。
     * <p>注意: 事实类意图下此字段会被节点侧强制视为 true (见 SelfRagProperties.onlyHowToComplete),
     * 但 LLM 仍可能给出真实判断, 故保留原值, 由节点决定是否采纳。</p>
     */
    @JsonProperty("complete")
    private boolean complete;

    /** 简短诊断理由 (用于日志/可观测性/面试 debug). LLM 输出, 可选。 */
    @JsonProperty("reason")
    private String reason;

    /**
     * 节点侧映射出的处置裁决 (不参与 LLM JSON 反序列化, 由节点根据三维 + 意图计算)。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Verdict verdict;

    /**
     * 构造一个"判别失败 → 直接放过"的兜底结果。
     * <p>judge LLM 调用异常/超时/解析失败时使用: 三维全 true → verdict=PASS,
     * 即"放过第1版", 不因 judge 自身故障而卡住用户或触发无意义重生成。</p>
     */
    public static SelfRagJudgement passOnError(String reason) {
        return SelfRagJudgement.builder()
                .grounded(true)
                .relevant(true)
                .complete(true)
                .reason(reason)
                .verdict(Verdict.PASS)
                .build();
    }

    /**
     * 处置裁决.
     */
    public enum Verdict {
        /** 三维全过: 采纳第1版, 跳过第2版生成 (收敛优化) */
        PASS,
        /** grounded=true 但 relevant/complete 不过: 同 context 换 prompt 生成第2版后对比 */
        RETRY_GEN,
        /** grounded=false: context 不对路, QueryRewrite 重检索后生成第2版再对比 */
        RETRY_RETRIEVE
    }
}