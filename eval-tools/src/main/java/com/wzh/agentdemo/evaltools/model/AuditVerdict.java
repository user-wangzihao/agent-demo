package com.wzh.agentdemo.evaltools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 对单个 chunk 的判定结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditVerdict {

    public enum Verdict {
        YES,      // 完全能回答
        PARTIAL,  // 部分相关
        NO,       // 不相关
        ERROR     // LLM 调用失败或返回非法
    }

    private Verdict verdict;
    private double confidence;
    private String reason;
    /** 调用的模型，便于报告区分 turbo/plus */
    private String model;
}
