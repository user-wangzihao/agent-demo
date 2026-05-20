package com.wzh.agentdemo.evaltools.rebuild;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * eval-set.txt 的 surgical 写回器 (评估 CI Batch 5-A).
 *
 * <p><b>核心约束</b>: 仅在每条 case 的 {@code chunk_id：} 行上追加新 chunk_id,
 * 其他所有字节 (空行、注释、大类标题、=== 分隔符、答案、问题等) 不动.</p>
 *
 * <h2>evalId 对齐策略</h2>
 *
 * <p>与 {@code EvalSetParser} 严格对齐: evalId 从 1 开始全局自增, 每碰到一个
 * {@code chunk_id：}/{@code chunk_id:} 行计为一个 case 终结, 计数器 +1.
 * 这依赖于 eval-set.txt 中每个 case 必有且仅有一个 chunk_id 行 (EvalSetParser 的硬性约定).</p>
 *
 * <h2>合并规则</h2>
 *
 * <ol>
 *   <li>读 chunk_id 行已有的 ID 列表 (容忍中英文逗号 / 空白分隔符)</li>
 *   <li>合并 toAppend 中该 evalId 对应的 ID, 去重 (LinkedHashSet 保持顺序)</li>
 *   <li>已存在的 ID 不重复加 (skipped 计数)</li>
 *   <li>新加的 ID 累加到行尾</li>
 *   <li>统一用英文逗号 {@code ,} 重新拼接整行</li>
 *   <li>保留原行的前缀 ("chunk_id：" 或 "chunk_id:") 和行尾换行</li>
 * </ol>
 *
 * <p><b>原文件中文逗号约定</b>: 用户已手动把 eval-set.txt 中 chunk_id 行的中文逗号
 * 转为英文逗号. 本类输出时统一用英文逗号. 即便原文件混用, 也会被本类规范化为英文逗号
 * (这是 by design 的副作用, 视为有益的规范化).</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 5-A)
 */
@Slf4j
public class EvalSetMerger {

    /** 兼容中英文冒号的 chunk_id 行识别 */
    private static final String[] CHUNK_ID_PREFIXES = {"chunk_id：", "chunk_id:"};

    /** 已有 chunk_id 解析时容忍中英文逗号 / 顿号 / 空白 */
    private static final String EXISTING_ID_SPLIT_REGEX = "[,，、\\s]+";

    /** 写回时统一用英文逗号 */
    private static final String OUTPUT_DELIMITER = ", ";

    /**
     * 执行 surgical 合并.
     *
     * @param originalText eval-set.txt 原文本
     * @param toAppendByEvalId key=evalId, value=该 evalId 应追加的 chunk_id 集合
     * @return 合并后的新文本 + 每条 case 的合并统计
     */
    public MergeResult merge(String originalText, Map<Integer, Set<String>> toAppendByEvalId) {
        if (originalText == null) originalText = "";

        // 按行扫描. 保留原换行符 (LF / CRLF) 由 split + 重新拼接逻辑保证.
        // 这里用 \\R 切, 但记录原换行符以便复原.
        String newline = detectNewline(originalText);
        String[] lines = originalText.split("\\R", -1);

        List<String> output = new ArrayList<>(lines.length);
        Map<Integer, CaseMergeStat> stats = new TreeMap<>();
        int evalId = 1;

        for (String line : lines) {
            int prefixLen = matchChunkIdPrefix(line);
            if (prefixLen < 0) {
                output.add(line);
                continue;
            }

            // 命中 chunk_id 行 — 这是 evalId 对应的 case
            String prefix = line.substring(0, prefixLen);
            String idsPart = line.substring(prefixLen);

            Set<String> existing = parseExistingIds(idsPart);
            Set<String> toAppend = toAppendByEvalId.getOrDefault(evalId, Set.of());

            CaseMergeStat stat = new CaseMergeStat();
            stat.setEvalId(evalId);
            stat.setOriginalCount(existing.size());

            // 用 LinkedHashSet 保留原有顺序, 再追加新 ID
            Set<String> merged = new LinkedHashSet<>(existing);
            for (String id : toAppend) {
                if (id == null || id.isBlank()) continue;
                if (merged.add(id)) {
                    stat.addedIds.add(id);
                } else {
                    stat.skippedIds.add(id);
                }
            }
            stat.setFinalCount(merged.size());
            stats.put(evalId, stat);

            // 拼接新行: 前缀保留, ID 用英文逗号
            String newLine = prefix + String.join(OUTPUT_DELIMITER, merged);
            output.add(newLine);

            evalId++;
        }

        String result = String.join(newline, output);
        return new MergeResult(result, stats);
    }

    // ==================== helper ====================

    /**
     * 若 line 以任意一个 {@link #CHUNK_ID_PREFIXES} 开头, 返回前缀长度; 否则 -1.
     * <p>容忍前导空白 (一般 eval-set.txt 没有, 但保险起见).</p>
     */
    private int matchChunkIdPrefix(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.length() == line.length()) {
            // 无前导空白
            for (String prefix : CHUNK_ID_PREFIXES) {
                if (line.startsWith(prefix)) return prefix.length();
            }
            return -1;
        }
        // 有前导空白 - 标准 eval-set.txt 不应该出现, 但仍兼容
        int leading = line.length() - trimmed.length();
        for (String prefix : CHUNK_ID_PREFIXES) {
            if (trimmed.startsWith(prefix)) return leading + prefix.length();
        }
        return -1;
    }

    /** 解析已有 ID 列表, 去重 (LinkedHashSet 保留原顺序) */
    private Set<String> parseExistingIds(String idsPart) {
        Set<String> result = new LinkedHashSet<>();
        if (idsPart == null || idsPart.isBlank()) return result;
        for (String t : idsPart.split(EXISTING_ID_SPLIT_REGEX)) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** 探测原文件的换行符 (LF / CRLF), 写回时保持一致 */
    private String detectNewline(String text) {
        if (text.contains("\r\n")) return "\r\n";
        if (text.contains("\n")) return "\n";
        return System.lineSeparator();
    }

    // ==================== 结果对象 ====================

    /** 合并结果 */
    @Data
    public static class MergeResult {
        private final String mergedText;
        private final Map<Integer, CaseMergeStat> statsByEvalId;
    }

    /** 单 case 的合并统计 */
    @Data
    public static class CaseMergeStat {
        private int evalId;
        private int originalCount;
        private int finalCount;
        /** 实际新增的 ID 列表 (LinkedHashSet 保序) */
        private final Set<String> addedIds = new LinkedHashSet<>();
        /** 跳过 (因为已存在) 的 ID 列表 */
        private final Set<String> skippedIds = new LinkedHashSet<>();

        public int addedCount() {
            return addedIds.size();
        }

        public int skippedCount() {
            return skippedIds.size();
        }
    }
}
