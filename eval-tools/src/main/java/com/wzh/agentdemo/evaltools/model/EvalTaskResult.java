package com.wzh.agentdemo.evaltools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用评估任务结果 (Batch 1 引入).
 *
 * <p>所有 EvalTask 实现都返回此类型, 由 {@code UniversalEvalReporter} 渲染为
 * 统一 Markdown 报告.</p>
 *
 * <p><b>状态约定</b>:
 * <ul>
 *   <li>{@link Status#SKIPPED} - 任务未实现 / 数据源缺失等, 报告里以"跳过"展示, 不参与统计</li>
 *   <li>{@link Status#SUCCESS} - 任务正常运行完毕 (即使 fail 用例存在, 也算 SUCCESS,
 *       只要任务本身没出异常)</li>
 *   <li>{@link Status#ERROR}   - 任务执行过程中抛异常, errorMessage 必填</li>
 * </ul>
 *
 * <p><b>metrics 自由扩展</b>: 通用 totalCount / passCount / failCount 之外的指标
 * (如 P50 延迟、MRR@5 数值) 一律塞进 metrics map. 报告器会按 key 字母序展示.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalTaskResult {

    /** 任务标识 (如 intent / route / retrieval / latency), 用于报告分组 */
    private String taskName;

    /** 任务展示名 (中文), 用于 markdown 标题 */
    private String displayName;

    /** 执行状态 */
    private Status status;

    /** 任务整体耗时 (毫秒) */
    private long elapsedMs;

    /** 错误信息 (status=ERROR 时必填) */
    private String errorMessage;

    /** 用例总数 (status=SKIPPED 时可为 0) */
    private int totalCount;

    /** 通过用例数 */
    private int passCount;

    /** 失败用例数 */
    private int failCount;

    /**
     * 自定义指标 (按 key 字母序在 markdown 表格中展示).
     * <p>典型 key: "accuracy" / "mrr@5" / "ndcg@5" / "p50_ms" / "p95_ms".
     * value 类型不限, 报告器调 toString().</p>
     */
    @Builder.Default
    private Map<String, Object> metrics = new LinkedHashMap<>();

    /**
     * 失败用例明细 (供报告器列举哪些 case 没过).
     * <p>每个 detail 一行字符串, 由 task 自己组装格式 (如 "evalId=3 query=xxx expected=A got=B").</p>
     */
    @Builder.Default
    private List<String> failureDetails = new ArrayList<>();

    /**
     * 备注 / 总结性说明 (可选, 报告里渲染在指标表下方).
     * <p>例如: "已禁用 LLM 兜底, 仅评估关键词分类器"、"基线对比未启用".</p>
     */
    private String summary;

    /** 计算通过率 (totalCount=0 时返回 0.0). */
    public double passRate() {
        return totalCount == 0 ? 0.0 : (double) passCount / totalCount;
    }

    /** 快捷工厂: SKIPPED 状态. */
    public static EvalTaskResult skipped(String taskName, String displayName, String reason) {
        return EvalTaskResult.builder()
                .taskName(taskName)
                .displayName(displayName)
                .status(Status.SKIPPED)
                .summary(reason)
                .build();
    }

    /** 快捷工厂: ERROR 状态. */
    public static EvalTaskResult error(String taskName, String displayName, String errorMessage) {
        return EvalTaskResult.builder()
                .taskName(taskName)
                .displayName(displayName)
                .status(Status.ERROR)
                .errorMessage(errorMessage)
                .build();
    }

    public enum Status {
        /** 已成功运行 (业务用例可能有 fail, 但任务本身正常) */
        SUCCESS,
        /** 未实现 / 数据缺失等原因跳过 */
        SKIPPED,
        /** 任务执行异常 */
        ERROR
    }
}
