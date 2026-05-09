package com.wzh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.config.RewriteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Query 改写服务.
 *
 * <p>用 qwen-turbo 把用户原始 query 改写为 N 条不同角度的查询,
 * 用于多路并行检索 + RRF 融合,提升 RAG 召回率.</p>
 *
 * <p><b>设计要点</b>:
 * <ul>
 *   <li>Few-shot prompt: 内嵌 2 个无关示例(避免对评估集过拟合)</li>
 *   <li>JSON 输出: 严格要求 {"rewrites": [...]} 格式,parser 兼容 ```json``` 包裹</li>
 *   <li>失败降级: 抛 RuntimeException,由 RagEvalAgentService 决定降级到 baseline</li>
 *   <li>解析鲁棒性: LLM 偶尔输出非纯 JSON,做了 markdown 代码块剥离</li>
 * </ul></p>
 *
 * <p><b>典型用法</b>:
 * <pre>{@code
 * List<String> rewrites = queryRewriteService.rewrite("快速涂色为什么失败?");
 * // ["快速涂色 失败 报错", "快速涂色功能 异常 解决方法"]
 * }</pre></p>
 *
 * @author wzh
 * @since 2026-05-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final RewriteProperties rewriteProperties;
    private final DashScopeService dashScopeService;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // System Prompt (few-shot)
    // =========================================================================

    /**
     * Query 改写的系统提示词.
     *
     * <p><b>设计原则</b>:
     * <ol>
     *   <li>明确角色: "查询改写助手"</li>
     *   <li>双角度改写: 关键词提取 + 措辞规范化(覆盖你失败 case 的两种类型)</li>
     *   <li>Few-shot: 2 个示例展示期望输出格式和风格</li>
     *   <li>边界约束: 不发明实体、错别字按猜测改正、长度上限</li>
     *   <li>严格 JSON: 不带任何解释,parser 友好</li>
     * </ol>
     *
     * <p><b>示例选择</b>: 用领域内但不在评估集里的 case (零件刻字、BOM 表数量),
     * 避免对 24 题评估集过拟合.</p>
     */
    private static final String SYSTEM_PROMPT = """
        你是一个查询改写助手。用户向技术文档系统提问,但他们的提问方式可能与文档措辞不一致。
        你的任务:根据用户的原始查询,生成 %d 条不同角度的改写查询,用于检索文档。
        
        改写要求:
        1. 第一条偏向【关键词提取】:抽取核心实体名词 + 关键动作/状态,去除口语化表达
        2. 第二条偏向【措辞规范化】:保留专有名词的前提下,主动添加文档常见的辅助词
           (如"操作步骤"、"使用流程"、"排查方法"、"解决方案"、"功能说明"、"作用"等),
           让查询更贴近文档章节标题/正文的表述风格
        3. 严禁同义改写专有功能名称:用户提到的功能名(如"赋属性"、"快速涂色"、"BOM 表")必须原样保留,
           不得改成"设置对象属性"、"颜色填充"等同义词;这些是软件内部固定术语,改了就检索不到
        4. 不要发明用户没提到的实体;如果原查询包含明显错别字,按最佳猜测改正
        5. 每条改写不超过 30 字,但不要过度压缩(改写过短会丢失检索信号,建议 8-25 字)
        6. 改写之间应有明显风格差异,避免雷同
        
        【输出格式】严格按 JSON 输出,不要任何解释、不要 markdown 包裹:
        {"rewrites": ["改写1", "改写2"]}
        
        【示例 1】
        原查询:那个零件刻字老是搞不定,字体显示不出来
        输出:{"rewrites": ["零件刻字 字体 显示异常", "零件刻字功能 字体无法显示 解决方法"]}
        
        【示例 2】
        原查询:BOM 表里数量不对怎么办?
        输出:{"rewrites": ["BOM表 数量 错误", "BOM表工具 数量统计异常 排查方法"]}
        
        【示例 3】
        原查询:如何使用零件刻字这个功能?
        输出:{"rewrites": ["零件刻字 操作步骤", "零件刻字功能 使用方法 操作流程"]}
        """;

    // =========================================================================
    // 主入口
    // =========================================================================

    /**
     * 把用户原始 query 改写为 N 条查询.
     *
     * @param query 用户原始 query
     * @return 改写后的 query 列表 (长度 = numRewrites);
     *         调用失败抛 RuntimeException,由调用方决定降级
     * @throws RuntimeException LLM 调用失败、JSON 解析失败、改写数量不符等
     */
    public List<String> rewrite(String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }

        long start = System.currentTimeMillis();
        int expectedNum = rewriteProperties.getNumRewrites();
        String systemPrompt = String.format(SYSTEM_PROMPT, expectedNum);

        try {
            // 1. 调 LLM
            String rawResponse = dashScopeService.chatOnce(
                    rewriteProperties.getModel(),
                    systemPrompt,
                    "原查询:" + query + "\n输出:",
                    rewriteProperties.getTemperature(),
                    rewriteProperties.getMaxTokens(),
                    null
            );

            // 2. 解析 JSON
            List<String> rewrites = parseRewrites(rawResponse);

            // 3. 校验数量
            if (rewrites.isEmpty()) {
                throw new RuntimeException("LLM 返回 0 条改写,可能格式错误: " + truncate(rawResponse, 200));
            }
            // 数量不等时容忍但记 warn — LLM 偶尔多/少 1 条很常见
            if (rewrites.size() != expectedNum) {
                log.warn("[REWRITE] 改写数量不符 expected={} got={}, 仍使用 query={}",
                        expectedNum, rewrites.size(), truncate(query, 50));
            }

            long latency = System.currentTimeMillis() - start;
            log.info("[REWRITE] 改写完成 original={} rewrites={} latency={}ms",
                    truncate(query, 40), rewrites, latency);
            return rewrites;

        } catch (RuntimeException e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[REWRITE] 改写失败 query={} latency={}ms err={}",
                    truncate(query, 40), latency, e.getMessage());
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[REWRITE] 改写异常 query={} latency={}ms",
                    truncate(query, 40), latency, e);
            throw new RuntimeException("Query 改写失败: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // JSON 解析(兼容 markdown 代码块包裹)
    // =========================================================================

    /**
     * 解析 LLM 返回的 JSON,提取 rewrites 数组.
     *
     * <p><b>容错策略</b>:
     * <ol>
     *   <li>剥离 markdown 代码块标记 (```json ... ``` / ``` ... ```)</li>
     *   <li>定位最外层 {} 边界,容忍前后噪音文本</li>
     *   <li>必须有 rewrites 字段且为数组</li>
     *   <li>过滤空字符串,trim 每条</li>
     * </ol></p>
     */
    private List<String> parseRewrites(String rawResponse) throws Exception {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            throw new RuntimeException("LLM 返回空响应");
        }

        String cleaned = stripMarkdownCodeBlock(rawResponse.trim());

        // 防御:如果 LLM 在 JSON 前后加了说明文字,定位最外层 {}
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new RuntimeException("LLM 响应中找不到 JSON 对象: " + truncate(rawResponse, 200));
        }
        String jsonStr = cleaned.substring(start, end + 1);

        JsonNode root = objectMapper.readTree(jsonStr);
        JsonNode rewritesNode = root.get("rewrites");
        if (rewritesNode == null || !rewritesNode.isArray()) {
            throw new RuntimeException("JSON 缺少 rewrites 数组字段: " + truncate(jsonStr, 200));
        }

        List<String> rewrites = new ArrayList<>();
        for (JsonNode item : rewritesNode) {
            if (item.isTextual()) {
                String text = item.asText().trim();
                if (!text.isEmpty()) {
                    rewrites.add(text);
                }
            }
        }
        return rewrites;
    }

    /**
     * 剥离 markdown 代码块标记.
     * <p>LLM 偶尔会输出 {@code ```json\n{...}\n```},尽管 prompt 已要求不要包裹.</p>
     */
    private String stripMarkdownCodeBlock(String s) {
        if (s.startsWith("```")) {
            // 去掉开头的 ```json 或 ```
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            } else {
                s = s.substring(3); // 防御:没换行的极端情况
            }
            // 去掉结尾的 ```
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3).trim();
            }
        }
        return s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // =========================================================================
    // 暴露 properties 给上层(便于 RagEvalAgentService 读取配置)
    // =========================================================================
    public RewriteProperties getProperties() {
        return rewriteProperties;
    }
}