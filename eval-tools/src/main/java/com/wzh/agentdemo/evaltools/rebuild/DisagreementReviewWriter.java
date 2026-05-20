package com.wzh.agentdemo.evaltools.rebuild;

import com.wzh.agentdemo.evaltools.model.AuditResult;
import com.wzh.agentdemo.evaltools.model.AuditVerdict;
import com.wzh.agentdemo.evaltools.model.ChunkCandidate;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 分歧裁决文件生成器 (评估 CI Batch 5-A, stage 1).
 *
 * <p>读 audit-report 的 disagreements (turbo=YES/PARTIAL & plus=NO 的 chunk),
 * 渲染为人类友好的 plain text 文件供人工 review.</p>
 *
 * <p><b>设计原则</b>:
 * <ul>
 *   <li>verdict 行紧贴 chunk 内容下方, 不需要切窗口</li>
 *   <li>留空 / 拼写错 = REJECT (默认安全), 必须明确写 ACCEPT 才接受</li>
 *   <li>同一文件内允许重复 evalId (一个 case 可能有多条分歧 chunk)</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 5-A)
 */
@Slf4j
public class DisagreementReviewWriter {

    private static final String SEPARATOR =
            "================================================================";

    /**
     * 写出分歧裁决文件.
     *
     * @param results audit-report 反序列化得到的 AuditResult 列表
     * @param outputPath 输出文件路径
     * @return 文件中包含的分歧 chunk 数量
     */
    public int write(List<AuditResult> results, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());

        StringBuilder sb = new StringBuilder();
        renderHeader(sb);

        int total = 0;
        for (AuditResult r : results) {
            if (r.getDisagreements() == null || r.getDisagreements().isEmpty()) continue;
            for (AuditResult.SuspectedMissing sm : r.getDisagreements()) {
                renderBlock(sb, r, sm);
                total++;
            }
        }

        if (total == 0) {
            sb.append("# (无分歧 chunk, 全部 suspectedMissing 已自动接受)\n");
        } else {
            renderFooter(sb, total);
        }

        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        log.info("分歧裁决文件已写出: {} (共 {} 个分歧 chunk 待 review)",
                outputPath.toAbsolutePath(), total);
        return total;
    }

    // ==================== 渲染细节 ====================

    private void renderHeader(StringBuilder sb) {
        sb.append("# ================ 分歧裁决文件 (Batch 5-A 半自动重建 ground truth) ================\n");
        sb.append("#\n");
        sb.append("# 每条 chunk 在 verdict 行填 ACCEPT 或 REJECT:\n");
        sb.append("#   ACCEPT - 加入 eval-set.txt 中对应 case 的 expectedChunks\n");
        sb.append("#   REJECT - 不加入 (默认; 留空 / 拼写错 都算 REJECT)\n");
        sb.append("#\n");
        sb.append("# 注意:\n");
        sb.append("#   1. 不要修改 [evalId=N] [chunk_id=xxx] 这两行, parser 靠它定位\n");
        sb.append("#   2. 不要删除 ================ 分隔线\n");
        sb.append("#   3. 编辑完毕后, 运行 EvalSetRebuilder --decisions=<this-file> 完成写回\n");
        sb.append("#\n");
        sb.append("# 已自动接受的 suspectedMissing (双模型都 YES/PARTIAL) 不在此文件中, 已记录在 audit-report.json\n");
        sb.append("\n");
    }

    private void renderBlock(StringBuilder sb, AuditResult r, AuditResult.SuspectedMissing sm) {
        ChunkCandidate c = sm.getCandidate();
        sb.append(SEPARATOR).append("\n");
        sb.append("[evalId=").append(r.getEvalCase().getEvalId())
                .append("] [chunk_id=").append(safe(c.getChunkId())).append("]\n");
        sb.append("[turbo=").append(verdictLine(sm.getTurboVerdict())).append("]\n");
        sb.append("[plus=").append(verdictLine(sm.getPlusVerdict())).append("]\n");
        sb.append("category=").append(safe(r.getEvalCase().getCategory())).append("\n");
        sb.append("feature=").append(safe(r.getEvalCase().getFeatureName())).append("\n");
        sb.append("query=").append(safe(r.getEvalCase().getQuery())).append("\n");
        sb.append("chunk_content:\n");
        sb.append("    ").append(truncate(safe(c.getContent()), 400).replace("\n", "\n    "))
                .append("\n");
        sb.append("verdict: \n");
        sb.append("\n");
    }

    private void renderFooter(StringBuilder sb, int total) {
        sb.append(SEPARATOR).append("\n");
        sb.append("# 共 ").append(total).append(" 个分歧 chunk 待裁决.\n");
        sb.append("# 编辑完毕后运行: EvalSetRebuilder --decisions=<this-file>\n");
    }

    private String verdictLine(AuditVerdict v) {
        if (v == null) return "无判定";
        return String.format("%s (conf=%.2f) — %s",
                v.getVerdict(), v.getConfidence(), safe(v.getReason()));
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + " ...(truncated)" : s;
    }
}
