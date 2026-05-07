package com.wzh.agentdemo.evaltools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条评估 case，从 eval-set.txt 解析得到。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCase {
    /** 全局自增 ID (1-based) */
    private int evalId;
    /** 大类: 问题类 / 使用方式类 / 功能介绍类 */
    private String category;
    /** 功能模块名，如 "快速涂色赋注解" */
    private String featureName;
    /** 用户问题 */
    private String query;
    /** 标准答案文本 */
    private String answer;
    /** 标注的 expectedChunks */
    @Builder.Default
    private List<String> expectedChunks = new ArrayList<>();
}
