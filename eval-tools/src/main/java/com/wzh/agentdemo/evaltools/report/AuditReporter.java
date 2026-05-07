package com.wzh.agentdemo.evaltools.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.model.AuditResult;
import com.wzh.agentdemo.evaltools.model.AuditVerdict;
import com.wzh.agentdemo.evaltools.model.ChunkCandidate;
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
 * 输出审计报告：JSON (机器读) + Markdown (人工 review)。
 */
@Slf4j
public class AuditReporter {

    private final ObjectMapper mapper;

    public AuditReporter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // enum 序列化为字符串
        this.mapper.findAndRegisterModules();
    }

    public void write(List<AuditResult> results, Map<String, ChunkCandidate> expectedDetails) throws IOException {
        Path outDir = Paths.get(AuditConfig.OUTPUT_DIR);
        Files.createDirectories(outDir);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path jsonPath = outDir.resolve("audit-report-" + stamp + ".json");
        Path mdPath = outDir.resolve("audit-report-" + stamp + ".md");

        // ===== JSON =====
        mapper.writeValue(jsonPath.toFile(), results);
        log.info("JSON 报告写入 {}", jsonPath.toAbsolutePath());

        // ===== Markdown =====
        StringBuilder md = new StringBuilder();
        renderMarkdown(md, results, expectedDetails, stamp);
        Files.writeString(mdPath, md.toString(), StandardCharsets.UTF_8);
        log.info("Markdown 报告写入 {}", mdPath.toAbsolutePath());
    }

    private void renderMarkdown(StringBuilder md, List<AuditResult> results,
                                Map<String, ChunkCandidate> expectedDetails, String stamp) {
        // ---- 全局摘要 ----
        int totalCases = results.size();
        long suspectedCases = results.stream().filter(r -> !r.getSuspectedMissing().isEmpty()).count();
        int totalSuspectedChunks = results.stream().mapToInt(r -> r.getSuspectedMissing().size()).sum();
        int totalDisagreements = results.stream().mapToInt(r -> r.getDisagreements().size()).sum();

        md.append("# Ground Truth Audit Report\n\n");
        md.append("- 生成时间: ").append(stamp).append("\n");
        md.append("- 审计 case 数: ").append(totalCases).append("\n");
        md.append("- 发现疑似漏标的 case 数: **").append(suspectedCases).append("**\n");
        md.append("- 疑似漏标 chunk 总数 (双模型一致): **").append(totalSuspectedChunks).append("**\n");
        md.append("- 模型分歧 chunk 数 (仅供参考): ").append(totalDisagreements).append("\n\n");

        md.append("> 判定规则: turbo 和 plus **同时**判 YES 或 PARTIAL 的 chunk 进入\"疑似漏标\"清单；\n");
        md.append("> 仅 turbo 判 YES/PARTIAL 但 plus 判 NO 的进入\"模型分歧\"清单。\n\n");
        md.append("---\n\n");

        // ---- 仅展示有疑似漏标的 case ----
        md.append("## 一、疑似漏标 case 详情\n\n");
        boolean hasAny = false;
        for (AuditResult r : results) {
            if (r.getSuspectedMissing().isEmpty()) continue;
            hasAny = true;
            renderCaseSection(md, r, expectedDetails, true);
        }
        if (!hasAny) md.append("*无疑似漏标。*\n\n");

        // ---- 模型分歧 case ----
        md.append("---\n\n## 二、模型分歧 case (仅供参考)\n\n");
        boolean hasDis = false;
        for (AuditResult r : results) {
            if (r.getDisagreements().isEmpty()) continue;
            hasDis = true;
            renderCaseSection(md, r, expectedDetails, false);
        }
        if (!hasDis) md.append("*无分歧。*\n\n");

        // ---- 全部 case 摘要表 ----
        md.append("---\n\n## 三、全部 case 摘要\n\n");
        md.append("| evalId | category | feature | 候选数 | 疑似漏标 | 分歧 | query |\n");
        md.append("|--------|----------|---------|--------|---------|------|-------|\n");
        for (AuditResult r : results) {
            md.append("| ").append(r.getEvalCase().getEvalId())
                    .append(" | ").append(r.getEvalCase().getCategory())
                    .append(" | ").append(safeMd(r.getEvalCase().getFeatureName()))
                    .append(" | ").append(r.getCandidateCount())
                    .append(" | ").append(r.getSuspectedMissing().size())
                    .append(" | ").append(r.getDisagreements().size())
                    .append(" | ").append(safeMd(truncate(r.getEvalCase().getQuery(), 40)))
                    .append(" |\n");
        }
    }

    private void renderCaseSection(StringBuilder md, AuditResult r,
                                   Map<String, ChunkCandidate> expectedDetails, boolean isSuspected) {
        md.append("### EvalId #").append(r.getEvalCase().getEvalId())
                .append(" (").append(r.getEvalCase().getCategory()).append(" / ")
                .append(safeMd(r.getEvalCase().getFeatureName())).append(")\n\n");
        md.append("**Query**: ").append(safeMd(r.getEvalCase().getQuery())).append("\n\n");
        md.append("**当前 expectedChunks**: ");
        for (String id : r.getEvalCase().getExpectedChunks()) {
            md.append("`").append(id).append("` ");
        }
        md.append("\n\n");

        md.append("**关键词**: ").append(String.join(", ", r.getKeywords())).append("\n\n");
        md.append("**候选总数**: ").append(r.getCandidateCount()).append("\n\n");

        List<AuditResult.SuspectedMissing> list = isSuspected ? r.getSuspectedMissing() : r.getDisagreements();
        int idx = 1;
        for (AuditResult.SuspectedMissing sm : list) {
            ChunkCandidate c = sm.getCandidate();
            String label = isSuspected ? "疑似漏标" : "分歧";
            md.append("#### ").append(label).append(" #").append(idx++)
                    .append(": `").append(c.getChunkId()).append("`\n\n");

            md.append("- **来源**: ").append(c.getSource());
            if (c.getVectorRank() != null) {
                md.append(" (向量召回 rank=").append(c.getVectorRank())
                        .append(", score=").append(String.format("%.4f", c.getVectorScore())).append(")");
            }
            md.append("\n");
            md.append("- **feature_name**: ").append(safeMd(c.getFeatureName())).append("\n");
            md.append("- **chunk_type**: ").append(safeMd(c.getChunkType())).append("\n");

            md.append("- **turbo 判定**: ").append(verdictMd(sm.getTurboVerdict())).append("\n");
            md.append("- **plus  判定**: ").append(verdictMd(sm.getPlusVerdict())).append("\n");

            md.append("\n<details><summary>📄 chunk 内容 (点击展开)</summary>\n\n```\n");
            md.append(c.getContent()).append("\n```\n</details>\n\n");

            if (isSuspected) {
                md.append("**建议操作**: ✅ 加入 evalId #").append(r.getEvalCase().getEvalId())
                        .append(" 的 expectedChunks，chunkId = `").append(c.getChunkId()).append("`\n\n");
            }
        }
        md.append("---\n\n");
    }

    private String verdictMd(AuditVerdict v) {
        if (v == null) return "*无*";
        String emoji = switch (v.getVerdict()) {
            case YES -> "✅";
            case PARTIAL -> "🟡";
            case NO -> "❌";
            case ERROR -> "⚠️";
        };
        return "%s **%s** (conf=%.2f) — %s".formatted(
                emoji, v.getVerdict(), v.getConfidence(), safeMd(v.getReason()));
    }

    private String truncate(String s, int len) {
        if (s == null) return "";
        return s.length() <= len ? s : s.substring(0, len) + "...";
    }

    private String safeMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
