package com.wzh.agentdemo.evaltools.rebuild;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分歧 chunk 的人工裁决决定 (评估 CI Batch 5-A).
 *
 * <p>对应 disagreement-review-{ts}.txt 中一个 chunk 块的 verdict 行内容.</p>
 *
 * <p><b>语义</b>:
 * <ul>
 *   <li>{@link Verdict#ACCEPT} - 此 chunk 应加入 eval-set.txt 该 case 的 expectedChunks</li>
 *   <li>{@link Verdict#REJECT} - 不加入 (默认; review 文件中 verdict 行留空也算 REJECT)</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 5-A)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisagreementDecision {

    public enum Verdict {
        ACCEPT,
        REJECT
    }

    /** 评估 case 的 evalId (与 eval-set.txt 中 case 的解析顺序一致, 1-based) */
    private int evalId;

    /** 候选 chunk 的 chunkId */
    private String chunkId;

    /** 人工裁决 */
    private Verdict verdict;
}
