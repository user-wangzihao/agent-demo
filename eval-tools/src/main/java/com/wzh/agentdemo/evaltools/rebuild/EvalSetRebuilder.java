package com.wzh.agentdemo.evaltools.rebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.model.AuditResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 评估集 ground truth 半自动重建工具 (评估 CI Batch 5-A).
 *
 * <p><b>使用场景</b>: 文档被重新学习导致 chunk_id 漂移, 既有 eval-set.txt 中的标注全部失效.
 * 本工具基于 {@link com.wzh.agentdemo.evaltools.GroundTruthAuditor} 跑出的
 * audit-report-{ts}.json, 把 LLM 推荐的新 chunk_id 半自动合并回 eval-set.txt.</p>
 *
 * <h2>两阶段流程</h2>
 *
 * <h3>Stage 1: 生成分歧裁决文件 (默认行为)</h3>
 * <pre>
 *   java -cp eval-tools.jar com.wzh.agentdemo.evaltools.rebuild.EvalSetRebuilder
 * </pre>
 * <p>读最新 audit-report-*.json, 生成 disagreement-review-{ts}.txt.
 * suspectedMissing (双模型都 YES) 部分将在 stage 2 自动接受, 不出现在 review 文件中.
 * 只有 disagreements (turbo YES + plus NO) 部分需要你 review.</p>
 *
 * <h3>Stage 2: 应用裁决 + 写回 eval-set.txt</h3>
 * <pre>
 *   java -cp eval-tools.jar com.wzh.agentdemo.evaltools.rebuild.EvalSetRebuilder \
 *        --decisions=rag-eval-output/disagreement-review-20260519-203000.txt
 * </pre>
 * <p>读 review 文件 (你编辑后的) + 原 audit-report.json, 完成:
 * <ul>
 *   <li>备份 eval-set.txt → eval-set.txt.bak</li>
 *   <li>每条 case 追加新 chunk_id (surgical, 只改 chunk_id 行)</li>
 *   <li>统计每条 case 的新增/跳过数量</li>
 * </ul>
 *
 * <h2>CLI 参数</h2>
 *
 * <ul>
 *   <li>{@code --decisions=<path>}    切换到 stage 2; 不带此参数则跑 stage 1</li>
 *   <li>{@code --audit-report=<path>} 手动指定 audit-report-*.json; 默认用 OUTPUT_DIR 下最新</li>
 *   <li>{@code --eval-set=<path>}     手动指定 eval-set.txt; 默认 src/main/resources/eval-set.txt</li>
 *   <li>{@code --dry-run}             stage 2 模式下, 不真的写回 eval-set.txt, 只打印 diff</li>
 * </ul>
 *
 * <p><b>与既有 GroundTruthAuditor 关系</b>: 0 改动, 0 依赖. GroundTruthAuditor 写报告,
 * 本类读报告. 完全解耦.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 5-A)
 */
@Slf4j
public class EvalSetRebuilder {

    private static final String DEFAULT_EVAL_SET_PATH = "D:\\intelligent_design\\agent-showcase\\eval-tools\\src\\main\\resources\\eval-set.txt";

    public static void main(String[] args) {
        try {
            new EvalSetRebuilder().run(args);
        } catch (Exception e) {
            log.error("EvalSetRebuilder 顶层异常", e);
            System.exit(1);
        }
    }

    public void run(String[] args) throws IOException {
        Args parsed = parseArgs(args);
        parsed.decisionsPath = Path.of("D:\\intelligent_design\\agent-showcase\\rag-eval-output\\disagreement-review-20260520-101622.txt");
        if (parsed.decisionsPath == null) {
            runStage1(parsed);
        } else {
            runStage2(parsed);
        }
    }

    // ==================== Stage 1 ====================

    private void runStage1(Args args) throws IOException {
        log.info("================ EvalSetRebuilder Stage 1: 生成分歧裁决文件 ================");

        Path auditPath = args.auditReportPath != null
                ? args.auditReportPath
                : findLatestAuditReport();
        log.info("使用 audit-report: {}", auditPath.toAbsolutePath());

        List<AuditResult> results = loadAuditReport(auditPath);
        summarizeAuditReport(results);

        String stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path reviewPath = Paths.get(AuditConfig.OUTPUT_DIR)
                .resolve("disagreement-review-" + stamp + ".txt");

        int disagreementCount = new DisagreementReviewWriter().write(results, reviewPath);

        log.info("================ Stage 1 完成 ================");
        log.info("分歧裁决文件: {}", reviewPath.toAbsolutePath());
        log.info("待你裁决的 chunk 数: {}", disagreementCount);
        if (disagreementCount == 0) {
            log.info("(无分歧, 可直接跑 stage 2 自动接受所有 suspectedMissing)");
        } else {
            log.info("下一步:");
            log.info("  1. 编辑该文件, 在每个 verdict: 行后填 ACCEPT 或 REJECT");
            log.info("  2. 运行: EvalSetRebuilder --decisions={}",
                    reviewPath.toAbsolutePath());
        }
    }

    // ==================== Stage 2 ====================

    private void runStage2(Args args) throws IOException {
        log.info("================ EvalSetRebuilder Stage 2: 应用裁决 + 写回 eval-set.txt ================");

        // 1. 加载 audit-report (拿到 suspectedMissing 自动接受清单)
        Path auditPath = args.auditReportPath != null
                ? args.auditReportPath
                : findLatestAuditReport();
        log.info("audit-report: {}", auditPath.toAbsolutePath());
        List<AuditResult> auditResults = loadAuditReport(auditPath);

        // 2. 加载 decisions (人工裁决结果)
        log.info("decisions:    {}", args.decisionsPath.toAbsolutePath());
        List<DisagreementDecision> decisions =
                new DisagreementReviewParser().parse(args.decisionsPath);

        // 3. 合并: suspectedMissing 全部 ACCEPT + decisions 中 ACCEPT 的
        Map<Integer, Set<String>> toAppend = buildAppendMap(auditResults, decisions);
        int totalAppend = toAppend.values().stream().mapToInt(Set::size).sum();
        log.info("待追加 chunk_id 总数: {} 条 (覆盖 {} 个 case)", totalAppend, toAppend.size());

        // 4. 读 eval-set.txt
        Path evalSetPath = args.evalSetPath != null
                ? args.evalSetPath
                : Paths.get(DEFAULT_EVAL_SET_PATH);
        if (!Files.exists(evalSetPath)) {
            throw new IOException("eval-set.txt 不存在: " + evalSetPath.toAbsolutePath()
                    + " (可用 --eval-set=<path> 指定)");
        }
        log.info("eval-set:     {}", evalSetPath.toAbsolutePath());
        String original = Files.readString(evalSetPath, StandardCharsets.UTF_8);

        // 5. surgical 合并
        EvalSetMerger.MergeResult result = new EvalSetMerger().merge(original, toAppend);

        // 6. 打印统计
        renderMergeStats(result);

        // 7. 写回 (除非 dry-run)
        if (args.dryRun) {
            log.info("[dry-run] 不写回 eval-set.txt. 合并文本长度 {} 字符 (原 {} 字符)",
                    result.getMergedText().length(), original.length());
            return;
        }

        // 备份
        Path backupPath = Paths.get(evalSetPath.toString() + ".bak");
        Files.copy(evalSetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("原文件已备份: {}", backupPath.toAbsolutePath());

        // 写回
        Files.writeString(evalSetPath, result.getMergedText(), StandardCharsets.UTF_8);
        log.info("================ Stage 2 完成 ================");
        log.info("写回成功: {}", evalSetPath.toAbsolutePath());
        log.info("回滚命令 (如需): cp {} {}",
                backupPath.toAbsolutePath(), evalSetPath.toAbsolutePath());
    }

    // ==================== 合并清单构造 ====================

    /**
     * 把 suspectedMissing (自动接受) 和 decisions 中的 ACCEPT 合并为
     * evalId -> Set<chunkId> 的 map.
     */
    private Map<Integer, Set<String>> buildAppendMap(List<AuditResult> auditResults,
                                                    List<DisagreementDecision> decisions) {
        Map<Integer, Set<String>> map = new TreeMap<>();

        // suspectedMissing 全部自动接受
        for (AuditResult r : auditResults) {
            int evalId = r.getEvalCase().getEvalId();
            for (AuditResult.SuspectedMissing sm : r.getSuspectedMissing()) {
                String id = sm.getCandidate() == null ? null : sm.getCandidate().getChunkId();
                if (id != null && !id.isBlank()) {
                    map.computeIfAbsent(evalId, k -> new LinkedHashSet<>()).add(id);
                }
            }
        }
        log.info("  suspectedMissing 自动接受: {} 个 case 共 {} 条 chunk_id",
                map.size(), map.values().stream().mapToInt(Set::size).sum());

        // decisions 中 ACCEPT 的
        int humanAccepted = 0;
        int humanRejected = 0;
        for (DisagreementDecision d : decisions) {
            if (d.getVerdict() == DisagreementDecision.Verdict.ACCEPT) {
                map.computeIfAbsent(d.getEvalId(), k -> new LinkedHashSet<>()).add(d.getChunkId());
                humanAccepted++;
            } else {
                humanRejected++;
            }
        }
        log.info("  人工裁决: ACCEPT={}, REJECT={}", humanAccepted, humanRejected);
        return map;
    }

    // ==================== I/O 辅助 ====================

    /**
     * 找 OUTPUT_DIR 下最新的 audit-report-*.json.
     */
    private Path findLatestAuditReport() throws IOException {
        Path outDir = Paths.get(AuditConfig.OUTPUT_DIR);
        if (!Files.exists(outDir)) {
            throw new IOException("OUTPUT_DIR 不存在: " + outDir.toAbsolutePath()
                    + " (先跑 GroundTruthAuditor)");
        }
        try (Stream<Path> stream = Files.list(outDir)) {
            Optional<Path> latest = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("audit-report-") && n.endsWith(".json");
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            return latest.orElseThrow(() -> new IOException(
                    "OUTPUT_DIR 下无 audit-report-*.json 文件: " + outDir.toAbsolutePath()));
        }
    }

    private List<AuditResult> loadAuditReport(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return Arrays.asList(mapper.readValue(path.toFile(), AuditResult[].class));
    }

    private void summarizeAuditReport(List<AuditResult> results) {
        int totalCases = results.size();
        long suspectedCases = results.stream()
                .filter(r -> r.getSuspectedMissing() != null && !r.getSuspectedMissing().isEmpty())
                .count();
        int totalSuspected = results.stream()
                .mapToInt(r -> r.getSuspectedMissing() == null ? 0 : r.getSuspectedMissing().size())
                .sum();
        int totalDisagree = results.stream()
                .mapToInt(r -> r.getDisagreements() == null ? 0 : r.getDisagreements().size())
                .sum();
        log.info("audit-report 摘要: {} cases, {} cases 含疑似漏标, suspected={}, disagreements={}",
                totalCases, suspectedCases, totalSuspected, totalDisagree);
    }

    private void renderMergeStats(EvalSetMerger.MergeResult result) {
        int totalAdded = 0;
        int totalSkipped = 0;
        for (Map.Entry<Integer, EvalSetMerger.CaseMergeStat> e
                : result.getStatsByEvalId().entrySet()) {
            EvalSetMerger.CaseMergeStat stat = e.getValue();
            if (stat.addedCount() == 0 && stat.skippedCount() == 0) continue;
            log.info("  #{}: orig={} → final={} (+{} new, skipped={})",
                    stat.getEvalId(), stat.getOriginalCount(), stat.getFinalCount(),
                    stat.addedCount(), stat.skippedCount());
            totalAdded += stat.addedCount();
            totalSkipped += stat.skippedCount();
        }
        log.info("  ===== 合并总计: +{} 新增, {} 跳过 (已存在) =====", totalAdded, totalSkipped);
    }

    // ==================== CLI 参数 ====================

    private static class Args {
        Path decisionsPath;
        Path auditReportPath;
        Path evalSetPath;
        boolean dryRun;
    }

    private Args parseArgs(String[] args) {
        Args a = new Args();
        if (args == null) return a;
        for (String raw : args) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.startsWith("--decisions=")) {
                a.decisionsPath = Paths.get(s.substring("--decisions=".length()));
            } else if (s.startsWith("--audit-report=")) {
                a.auditReportPath = Paths.get(s.substring("--audit-report=".length()));
            } else if (s.startsWith("--eval-set=")) {
                a.evalSetPath = Paths.get(s.substring("--eval-set=".length()));
            } else if (s.equals("--dry-run")) {
                a.dryRun = true;
            } else if (!s.isEmpty()) {
                log.warn("未识别的参数 (忽略): {}", s);
            }
        }
        return a;
    }
}
