package com.wzh.agentdemo.evaltools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 case 的审计结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditResult {

    private EvalCase evalCase;

    /** 提取出的关键词，写进报告便于人工 review */
    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    /** 候选 chunk 总数 (去重后) */
    private int candidateCount;

    /** 疑似漏标的 chunk 列表 (双模型都判 YES/PARTIAL) */
    @Builder.Default
    private List<SuspectedMissing> suspectedMissing = new ArrayList<>();

    /** 仅 turbo 判 YES/PARTIAL，但 plus 判 NO 的分歧 case，仅供参考 */
    @Builder.Default
    private List<SuspectedMissing> disagreements = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuspectedMissing {
        private ChunkCandidate candidate;
        private AuditVerdict turboVerdict;
        private AuditVerdict plusVerdict;
    }
}
