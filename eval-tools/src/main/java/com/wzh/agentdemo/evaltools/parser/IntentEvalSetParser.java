package com.wzh.agentdemo.evaltools.parser;

import com.wzh.agentdemo.evaltools.model.IntentEvalCase;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 解析 {@code eval-set-intent.txt} (Batch 1 引入, 与 {@link EvalSetParser} 并列).
 *
 * <p><b>预期格式</b> (与 eval-set.txt 风格对齐, 复用 ===== 分大类、空行分 case):</p>
 * <pre>
 * 闲聊类
 *
 * 问题：你好
 * expected_intent：chitchat
 * expected_route：chitchat_answer
 *
 * 问题：你是谁
 * expected_intent：chitchat
 * expected_route：chitchat_answer
 *
 * ========================================================================================================================
 *
 * 管理员指令类
 *
 * 问题：还有多少文档没学习
 * expected_intent：admin_command
 * user_role：admin
 * expected_route：admin_agent
 *
 * 问题：还有多少文档没学习
 * expected_intent：admin_command
 * user_role：user
 * expected_route：feature_resolve
 *
 * ========================================================================================================================
 *
 * 工单类
 *
 * 问题：转人工吧
 * expected_intent：default
 * expected_route：ticket_agent
 *
 * ========================================================================================================================
 *
 * 兜底类
 *
 * 问题：苹果好吃还是橘子好吃
 * expected_intent：default
 * expected_route：knowledge_answer
 * </pre>
 *
 * <p><b>字段约定</b>:
 * <ul>
 *   <li>{@code 问题：}        - 必填. 用户原始 query.</li>
 *   <li>{@code expected_intent：} - 必填. Intent.code 字符串 (chitchat / admin_command 等).</li>
 *   <li>{@code expected_route：}  - 可选. Graph 节点名 (chitchat_answer / admin_agent 等).</li>
 *   <li>{@code user_role：}       - 可选. 默认 "user". 仅 admin_command 场景需要标 admin.</li>
 * </ul>
 *
 * <p><b>容错</b>: 中英文冒号都支持 (与 EvalSetParser 一致); 缺失 query 或 expectedIntent
 * 的 case 跳过并打 warn.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 1)
 */
@Slf4j
public class IntentEvalSetParser {

    /** 支持的大类标题 (与示例数据保持一致, 解析时识别这些行作为 category 起点) */
    private static final List<String> KNOWN_CATEGORIES = Arrays.asList(
            "闲聊类", "管理员指令类", "工单类", "兜底类"
    );

    public List<IntentEvalCase> parse(String fullText) {
        List<IntentEvalCase> result = new ArrayList<>();
        if (fullText == null || fullText.isBlank()) {
            log.warn("eval-set-intent.txt 内容为空, 返回空列表");
            return result;
        }

        // 按分隔线切大类 (与 EvalSetParser 同款正则)
        String[] sections = fullText.split("(?m)^={5,}.*$");

        int evalIdSeq = 1;
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;

            String[] lines = trimmed.split("\\R");
            String category = null;
            int startLine = 0;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty()) continue;
                if (KNOWN_CATEGORIES.contains(l)) {
                    category = l;
                    startLine = i + 1;
                    break;
                }
                // 未识别到标题, 按"未知"处理 (与 EvalSetParser 一致的兜底)
                category = "未知";
                startLine = i;
                break;
            }

            List<List<String>> caseBlocks = splitIntoCaseBlocks(lines, startLine);
            for (List<String> block : caseBlocks) {
                IntentEvalCase ec = parseSingleCase(block, category, evalIdSeq);
                if (ec != null) {
                    result.add(ec);
                    evalIdSeq++;
                }
            }
        }

        log.info("意图评估集解析完成, 共 {} 个 case", result.size());
        result.stream()
                .collect(Collectors.groupingBy(IntentEvalCase::getCategory, Collectors.counting()))
                .forEach((k, v) -> log.info("  {} : {} 个", k, v));
        return result;
    }

    /** 在一个大类内, 按空行将 lines 切成多个 case 块 (与 EvalSetParser 同款). */
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

    private IntentEvalCase parseSingleCase(List<String> block, String category, int evalId) {
        String query = null;
        String expectedIntent = null;
        String expectedRoute = null;
        String userRole = "user";

        for (String line : block) {
            String value;
            if ((value = stripPrefix(line, "问题")) != null) {
                query = value;
            } else if ((value = stripPrefix(line, "expected_intent")) != null) {
                expectedIntent = value;
            } else if ((value = stripPrefix(line, "expected_route")) != null) {
                expectedRoute = value;
            } else if ((value = stripPrefix(line, "user_role")) != null) {
                userRole = value;
            }
            // 其他行 (如标注者注释) 一律忽略
        }

        if (query == null || query.isBlank()) {
            log.warn("跳过缺失 query 的 case (category={}): {}", category, block);
            return null;
        }
        if (expectedIntent == null || expectedIntent.isBlank()) {
            log.warn("跳过缺失 expected_intent 的 case (category={}, query={})", category, query);
            return null;
        }

        return IntentEvalCase.builder()
                .evalId(evalId)
                .category(category)
                .query(query)
                .expectedIntent(expectedIntent)
                .expectedRoute(expectedRoute)
                .userRole(userRole)
                .build();
    }

    /**
     * 若 line 以 {@code prefix} (后接中文或英文冒号) 开头, 返回冒号后的 trim 值; 否则 null.
     */
    private String stripPrefix(String line, String prefix) {
        if (!line.startsWith(prefix)) return null;
        if (line.length() <= prefix.length()) return null;
        char sep = line.charAt(prefix.length());
        if (sep != '：' && sep != ':') return null;
        return line.substring(prefix.length() + 1).trim();
    }
}
