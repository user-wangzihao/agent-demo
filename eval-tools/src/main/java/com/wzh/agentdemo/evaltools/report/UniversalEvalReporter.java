package com.wzh.agentdemo.evaltools.report;

import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 评估 CI 综合 Markdown 报告器 (Batch 1 引入).
 *
 * <p>与既有 {@link AuditReporter} 并列存在:
 * <ul>
 *   <li>AuditReporter (零改动) - 仅服务 GroundTruthAuditor 的漏标审计</li>
 *   <li>UniversalEvalReporter  - 服务 EvalRunner 的多任务评估综合报告</li>
 * </ul>
 *
 * <p><b>输出</b>: {@code rag-eval-output/eval-report-{时间戳}.md}
 * (与既有 audit-report-*.md 同目录, 文件前缀区分)</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 1)
 */
@Slf4j
public class UniversalEvalReporter {

    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 输出综合评估报告.
     *
     * @param results 所有任务的执行结果, 顺序即为 markdown 中的展示顺序
     * @return 报告文件绝对路径
     */
    public Path write(List<EvalTaskResult> results) throws IOException {
        Path outDir = Paths.get(AuditConfig.OUTPUT_DIR);
        Files.createDirectories(outDir);

        String stamp = LocalDateTime.now().format(STAMP_FMT);
        Path mdPath = outDir.resolve("eval-report-" + stamp + ".md");

        StringBuilder md = new StringBuilder();
        renderHeader(md, stamp, results);
        renderSummaryTable(md, results);
        for (EvalTaskResult r : results) {
            renderTaskSection(md, r);
        }
        renderFooter(md);

        Files.writeString(mdPath, md.toString(), StandardCharsets.UTF_8);
        log.info("评估 CI 综合报告写入 {}", mdPath.toAbsolutePath());
        return mdPath;
    }

    // ==================== 渲染细节 ====================

    private void renderHeader(StringBuilder md, String stamp, List<EvalTaskResult> results) {
        md.append("# AgentDemo 评估 CI 报告\n\n");
        md.append("- **生成时间**: ").append(stamp).append("\n");
        md.append("- **任务数**: ").append(results.size()).append("\n");
        long success = results.stream().filter(r -> r.getStatus() == EvalTaskResult.Status.SUCCESS).count();
        long skipped = results.stream().filter(r -> r.getStatus() == EvalTaskResult.Status.SKIPPED).count();
        long error = results.stream().filter(r -> r.getStatus() == EvalTaskResult.Status.ERROR).count();
        md.append("- **状态分布**: SUCCESS=").append(success)
                .append(" | SKIPPED=").append(skipped)
                .append(" | ERROR=").append(error).append("\n\n");
    }

    private void renderSummaryTable(StringBuilder md, List<EvalTaskResult> results) {
        md.append("## 总览\n\n");
        md.append("| 任务 | 状态 | 用例数 | 通过 | 失败 | 通过率 | 耗时 (ms) |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (EvalTaskResult r : results) {
            md.append("| ").append(r.getDisplayName())
                    .append(" | ").append(statusBadge(r.getStatus()))
                    .append(" | ").append(r.getTotalCount())
                    .append(" | ").append(r.getPassCount())
                    .append(" | ").append(r.getFailCount())
                    .append(" | ").append(formatPercent(r.passRate()))
                    .append(" | ").append(r.getElapsedMs())
                    .append(" |\n");
        }
        md.append("\n");
    }

    private void renderTaskSection(StringBuilder md, EvalTaskResult r) {
        md.append("## ").append(r.getDisplayName())
                .append(" (`").append(r.getTaskName()).append("`)\n\n");
        md.append("- **状态**: ").append(statusBadge(r.getStatus())).append("\n");

        if (r.getStatus() == EvalTaskResult.Status.SKIPPED) {
            md.append("- **跳过原因**: ").append(safe(r.getSummary())).append("\n\n");
            return;
        }

        if (r.getStatus() == EvalTaskResult.Status.ERROR) {
            md.append("- **错误**: ").append(safe(r.getErrorMessage())).append("\n\n");
            return;
        }

        // SUCCESS 分支
        md.append("- **用例总数**: ").append(r.getTotalCount())
                .append(" (通过 ").append(r.getPassCount())
                .append(", 失败 ").append(r.getFailCount()).append(")\n");
        md.append("- **通过率**: ").append(formatPercent(r.passRate())).append("\n");
        md.append("- **耗时**: ").append(r.getElapsedMs()).append(" ms\n");

        // metrics 表
        if (r.getMetrics() != null && !r.getMetrics().isEmpty()) {
            md.append("\n### 指标\n\n");
            md.append("| 指标 | 数值 |\n|---|---|\n");
            for (Map.Entry<String, Object> e : r.getMetrics().entrySet()) {
                md.append("| ").append(e.getKey()).append(" | ")
                        .append(e.getValue() == null ? "-" : e.getValue().toString())
                        .append(" |\n");
            }
        }

        // failure details
        if (r.getFailureDetails() != null && !r.getFailureDetails().isEmpty()) {
            md.append("\n### 失败用例 (前 20 条)\n\n");
            int limit = Math.min(20, r.getFailureDetails().size());
            for (int i = 0; i < limit; i++) {
                md.append("- ").append(r.getFailureDetails().get(i)).append("\n");
            }
            if (r.getFailureDetails().size() > limit) {
                md.append("- ... (共 ").append(r.getFailureDetails().size())
                        .append(" 条, 仅展示前 ").append(limit).append(" 条)\n");
            }
        }

        // summary
        if (r.getSummary() != null && !r.getSummary().isBlank()) {
            md.append("\n> ").append(r.getSummary()).append("\n");
        }
        md.append("\n");
    }

    private void renderFooter(StringBuilder md) {
        md.append("---\n\n");
        md.append("*由 EvalRunner 生成. 既有 GroundTruthAuditor 报告 (audit-report-*.md) 不受影响.*\n");
    }

    private String statusBadge(EvalTaskResult.Status s) {
        return switch (s) {
            case SUCCESS -> "✅ SUCCESS";
            case SKIPPED -> "⏭️ SKIPPED";
            case ERROR -> "❌ ERROR";
        };
    }

    private String formatPercent(double v) {
        return String.format("%.2f%%", v * 100);
    }

    private String safe(String s) {
        return s == null ? "-" : s;
    }
}
