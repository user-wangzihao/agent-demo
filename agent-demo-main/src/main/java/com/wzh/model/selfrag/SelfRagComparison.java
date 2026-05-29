package com.wzh.model.selfrag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Self-RAG 两版答案 pairwise 对比择优结果 (Self-RAG Batch 1).
 *
 * <p><b>为什么用 pairwise 而非分别打分</b>: pointwise 打分 (给 A 打 7.5、给 B 打 8.0 再比大小)
 * 噪声大、阈值难定、跨调用不稳定。pairwise (把 A、B 同时给 judge, 让它直接二选一) 在
 * LLM-as-Judge 实践中显著更稳定 — judge 做相对判断比绝对打分容易得多。</p>
 *
 * <p><b>winner_acceptable 的作用 (假问题防御)</b>: 即使选出了较好的一版, 也可能两版都不合格
 * (知识库压根没这条信息, 两版都在编)。{@code winnerAcceptable=false} 时, 节点不采纳 winner,
 * 而是返回 {@link com.wzh.config.SelfRagProperties#getGiveUpFallback() 兜底话术} 且不写缓存。</p>
 *
 * <p>judge LLM 输出 JSON 结构:
 * <pre>
 * { "winner": "A" | "B", "winner_acceptable": true|false, "reason": "..." }
 * </pre></p>
 *
 * @author wzh
 * @since 2026-05-28 (Self-RAG Batch 1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SelfRagComparison {

    /** 胜出版本: "A" (第1版) 或 "B" (第2版). LLM 输出。 */
    @JsonProperty("winner")
    private String winner;

    /**
     * 胜出版本是否达到可接受质量 (反假问题).
     * false = 赢的这版也不合格 → 触发兜底话术, 不写缓存。LLM 输出。
     */
    @JsonProperty("winner_acceptable")
    private boolean winnerAcceptable;

    /** 简短对比理由 (日志/可观测性/面试 debug). LLM 输出, 可选。 */
    @JsonProperty("reason")
    private String reason;

    /** winner 是否指向第2版 (B)。容错: 非 "B" 一律视为 A。 */
    public boolean picksSecondVersion() {
        return "B".equalsIgnoreCase(winner == null ? "" : winner.trim());
    }

    /**
     * 构造一个"对比失败 → 采纳第1版 A"的兜底结果。
     * <p>judge 对比调用异常/超时/解析失败时使用: 默认信任第1版且视为可接受,
     * 不因 judge 故障而误触发兜底话术 (那会把一个可能合格的答案换成"没找到")。</p>
     */
    public static SelfRagComparison pickFirstOnError(String reason) {
        return SelfRagComparison.builder()
                .winner("A")
                .winnerAcceptable(true)
                .reason(reason)
                .build();
    }
}