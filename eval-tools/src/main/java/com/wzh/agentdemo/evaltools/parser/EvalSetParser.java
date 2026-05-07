package com.wzh.agentdemo.evaltools.parser;

import com.wzh.agentdemo.evaltools.model.EvalCase;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 解析 eval-set.txt 文本格式。
 *
 * <p>预期格式：</p>
 * <pre>
 * 问题类
 *
 * 赋注解属性工具
 * 功能：赋注解属性工具         (可选行，等价于第一行)
 * 问题：xxx
 * 答案：xxx
 * chunk_id：id1，id2
 *
 * (空行隔开下一个 case)
 *
 * ========================================================================================================================
 *
 * 使用方式类
 * ...
 * </pre>
 */
@Slf4j
public class EvalSetParser {

    private static final String SEPARATOR_PREFIX = "====";

    /** 中文/英文逗号都支持 */
    private static final String CHUNK_ID_DELIMITER_REGEX = "[,，]";

    public List<EvalCase> parse(String fullText) {
        List<EvalCase> result = new ArrayList<>();
        // 按分隔线切大类
        String[] sections = fullText.split("(?m)^={5,}.*$");

        int evalIdSeq = 1;
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;

            // 第一行非空内容应当是大类名
            String[] lines = trimmed.split("\\R");
            if (lines.length == 0) continue;

            String category = null;
            int startLine = 0;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty()) continue;
                if (l.equals("问题类") || l.equals("使用方式类") || l.equals("功能介绍类")) {
                    category = l;
                    startLine = i + 1;
                    break;
                }
                // 没识别到大类标题，就把整段当做一个未知分类处理
                category = "未知";
                startLine = i;
                break;
            }

            // 在这个大类内按"空行 或 下一 case 起始"切 case
            List<List<String>> caseBlocks = splitIntoCaseBlocks(lines, startLine);
            for (List<String> block : caseBlocks) {
                EvalCase ec = parseSingleCase(block, category, evalIdSeq);
                if (ec != null) {
                    result.add(ec);
                    evalIdSeq++;
                }
            }
        }

        log.info("解析完成，共 {} 个 case", result.size());
        // 按类别统计
        result.stream()
                .collect(Collectors.groupingBy(EvalCase::getCategory, Collectors.counting()))
                .forEach((k, v) -> log.info("  {} : {} 个", k, v));
        return result;
    }

    /**
     * 在一个大类内，按空行将 lines 切成多个 case 块。
     */
    private List<List<String>> splitIntoCaseBlocks(String[] lines, int startLine) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (int i = startLine; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().isEmpty()) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(l.trim());
            }
        }
        if (!current.isEmpty()) blocks.add(current);
        return blocks;
    }

    private EvalCase parseSingleCase(List<String> block, String category, int evalId) {
        // block 里期望有: [功能名(首行)], (功能：xxx 可选), 问题：xxx, 答案：xxx (可多行), chunk_id：xxx
        String featureName = null;
        String query = null;
        StringBuilder answerBuilder = new StringBuilder();
        List<String> chunkIds = new ArrayList<>();

        // 记录答案累积阶段：从 "答案：" 开始，到 "chunk_id" 之前结束
        boolean inAnswer = false;

        for (int i = 0; i < block.size(); i++) {
            String line = block.get(i);
            if (i == 0 && !line.startsWith("功能：") && !line.startsWith("问题：")
                    && !line.startsWith("答案：") && !line.startsWith("chunk_id")) {
                // 第一行无前缀，认作功能名
                featureName = line;
                continue;
            }

            if (line.startsWith("功能：")) {
                String v = line.substring("功能：".length()).trim();
                if (featureName == null) featureName = v;
                inAnswer = false;
            } else if (line.startsWith("问题：")) {
                query = line.substring("问题：".length()).trim();
                inAnswer = false;
            } else if (line.startsWith("答案：")) {
                String v = line.substring("答案：".length()).trim();
                answerBuilder.setLength(0);
                answerBuilder.append(v);
                inAnswer = true;
            } else if (line.startsWith("chunk_id")) {
                // 形如 "chunk_id：id1，id2" 或 "chunk_id:id1,id2"
                int colonIdx = -1;
                for (int j = 0; j < line.length(); j++) {
                    char c = line.charAt(j);
                    if (c == '：' || c == ':') {
                        colonIdx = j;
                        break;
                    }
                }
                if (colonIdx > 0) {
                    String idsPart = line.substring(colonIdx + 1).trim();
                    chunkIds = Arrays.stream(idsPart.split(CHUNK_ID_DELIMITER_REGEX))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                }
                inAnswer = false;
            } else if (inAnswer) {
                // 答案多行追加
                answerBuilder.append("\n").append(line);
            }
        }

        if (query == null || chunkIds.isEmpty()) {
            log.warn("跳过格式不完整的 case (category={}): {}", category, block);
            return null;
        }

        return EvalCase.builder()
                .evalId(evalId)
                .category(category)
                .featureName(featureName)
                .query(query)
                .answer(answerBuilder.toString())
                .expectedChunks(chunkIds)
                .build();
    }
}
