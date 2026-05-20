package com.wzh.agentdemo.evaltools.rebuild;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分歧裁决文件解析器 (评估 CI Batch 5-A, stage 2 输入端).
 *
 * <p>读取 {@link DisagreementReviewWriter} 写出 + 人工编辑后的文件,
 * 抽取每个块的 [evalId=N] [chunk_id=xxx] + verdict 三要素.</p>
 *
 * <p><b>容错</b>:
 * <ul>
 *   <li>verdict 大小写不敏感 (ACCEPT/accept/Accept 都识别)</li>
 *   <li>verdict 留空 → REJECT (默认安全)</li>
 *   <li>verdict 拼写错 / 不在 {ACCEPT, REJECT} → REJECT + warn 日志</li>
 *   <li>缺失 evalId / chunkId 行 → 跳过该块 + warn</li>
 *   <li># 开头的注释行忽略</li>
 *   <li>不需要识别 SEPARATOR, 完全基于 [evalId=...] 行作为新块起点</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 5-A)
 */
@Slf4j
public class DisagreementReviewParser {

    // [evalId=12] / [chunk_id=abc123] / verdict: ACCEPT
    private static final Pattern EVAL_ID_PATTERN = Pattern.compile("\\[evalId=(\\d+)\\]");
    private static final Pattern CHUNK_ID_PATTERN = Pattern.compile("\\[chunk_id=([^\\]]+)\\]");
    private static final Pattern VERDICT_PATTERN = Pattern.compile("(?i)^verdict:\\s*(\\S.*)?$");

    public List<DisagreementDecision> parse(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return parseContent(content);
    }

    /** package-private for testing */
    List<DisagreementDecision> parseContent(String content) {
        List<DisagreementDecision> result = new ArrayList<>();
        if (content == null || content.isBlank()) return result;

        String[] lines = content.split("\\R");

        // 当前正在收集的块的状态; 碰到下一个 [evalId=...] 就 commit 上一个
        Integer curEvalId = null;
        String curChunkId = null;
        DisagreementDecision.Verdict curVerdict = null;

        int blockStartLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();

            if (line.isEmpty() || line.startsWith("#") || line.startsWith("===")) {
                continue;
            }

            // 先看是否是块头: [evalId=N] (可能与 [chunk_id=xxx] 同行也可能分行)
            Matcher mEval = EVAL_ID_PATTERN.matcher(line);
            Matcher mChunk = CHUNK_ID_PATTERN.matcher(line);

            if (mEval.find()) {
                // 提交上一个块
                commit(result, curEvalId, curChunkId, curVerdict, blockStartLine);

                // 开新块
                curEvalId = Integer.parseInt(mEval.group(1));
                curChunkId = null;
                curVerdict = null;
                blockStartLine = i + 1;

                // 同行可能也带 chunk_id
                if (mChunk.find()) {
                    curChunkId = mChunk.group(1).trim();
                }
                continue;
            }

            // chunk_id 单独一行
            if (mChunk.find() && curChunkId == null && curEvalId != null) {
                curChunkId = mChunk.group(1).trim();
                continue;
            }

            // verdict 行
            Matcher mVerdict = VERDICT_PATTERN.matcher(line);
            if (mVerdict.matches() && curEvalId != null) {
                String val = mVerdict.group(1);
                curVerdict = parseVerdictValue(val, curEvalId, curChunkId, i + 1);
            }
        }
        // 提交最后一个块
        commit(result, curEvalId, curChunkId, curVerdict, blockStartLine);

        long accepted = result.stream()
                .filter(d -> d.getVerdict() == DisagreementDecision.Verdict.ACCEPT)
                .count();
        log.info("分歧裁决文件解析完成: 共 {} 条决定 (ACCEPT={}, REJECT={})",
                result.size(), accepted, result.size() - accepted);
        return result;
    }

    private void commit(List<DisagreementDecision> result,
                        Integer evalId, String chunkId,
                        DisagreementDecision.Verdict verdict, int blockStartLine) {
        if (evalId == null) return; // 还没开始过任何块
        if (chunkId == null || chunkId.isBlank()) {
            log.warn("跳过块 (起始行 {}): 缺失 chunk_id, evalId={}", blockStartLine, evalId);
            return;
        }
        // verdict 缺失视为 REJECT
        DisagreementDecision.Verdict finalV =
                verdict == null ? DisagreementDecision.Verdict.REJECT : verdict;

        result.add(DisagreementDecision.builder()
                .evalId(evalId)
                .chunkId(chunkId)
                .verdict(finalV)
                .build());
    }

    /**
     * 解析 verdict 字段值.
     * 空 / 异常 → REJECT (默认安全); 拼写错时 warn 但仍 REJECT.
     */
    private DisagreementDecision.Verdict parseVerdictValue(String val, int evalId,
                                                           String chunkId, int line) {
        if (val == null || val.isBlank()) {
            return DisagreementDecision.Verdict.REJECT;
        }
        String upper = val.trim().toUpperCase();
        if (upper.startsWith("ACCEPT") || upper.equals("A") || upper.equals("YES")) {
            return DisagreementDecision.Verdict.ACCEPT;
        }
        if (upper.startsWith("REJECT") || upper.equals("R") || upper.equals("NO")) {
            return DisagreementDecision.Verdict.REJECT;
        }
        log.warn("第 {} 行 verdict 值无法识别: '{}' (evalId={}, chunkId={}), 按 REJECT 处理",
                line, val, evalId, chunkId);
        return DisagreementDecision.Verdict.REJECT;
    }
}
