package com.wzh.agentdemo.evaltools.task;

import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.http.EvalHttpClient;
import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import com.wzh.agentdemo.evaltools.model.IntentEvalCase;
import com.wzh.agentdemo.evaltools.parser.IntentEvalSetParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 意图分类准确率评估任务 (评估 CI 主线 Batch 3 真实现).
 *
 * <p><b>评估流程</b>:
 * <ol>
 *   <li>从 classpath 加载 {@code eval-set-intent.txt}, 由 {@link IntentEvalSetParser}
 *       解析为 {@link IntentEvalCase} 列表</li>
 *   <li>每条 case 通过 {@link EvalHttpClient} 调主应用 {@code /internal/eval/intent}</li>
 *   <li>响应中的 {@code intent} 字段与 case 的 expectedIntent 严格比对</li>
 *   <li>汇总: overall accuracy + per-category accuracy + source 分布 + 失败用例列表</li>
 * </ol>
 *
 * <p><b>失败用例</b>: failureDetails 给出"evalId / category / query / expected / actual / source"
 * 一行字符串, 方便定位是关键词字典漏配还是 LLM 误判.</p>
 *
 * <p><b>SKIPPED 触发条件</b>:
 * <ul>
 *   <li>{@code eval-set-intent.txt} 不在 classpath (Batch 2 未交付时)</li>
 *   <li>主应用不可达 (第一次连通性探测就失败时整体跳过, 不浪费 21 次 HTTP)</li>
 * </ul>
 *
 * <p><b>不做的事</b>: 不评估 expectedRoute, 那是 Batch 4 的 RouteEvalTask 的职责.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 3 真实现; Batch 1 骨架替换)
 */
@Slf4j
public class IntentEvalTask implements EvalTask {

    public static final String TASK_NAME = "intent";
    public static final String DISPLAY_NAME = "意图分类准确率";

    private static final String INTENT_ENDPOINT = "/internal/eval/intent";

    private final EvalHttpClient http;

    public IntentEvalTask() {
        this(new EvalHttpClient());
    }

    /** 测试友好的构造器 (允许注入 mock 客户端). */
    public IntentEvalTask(EvalHttpClient http) {
        this.http = http;
    }

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public EvalTaskResult run() {
        long t0 = System.currentTimeMillis();
        try {
            return doRun(t0);
        } catch (Throwable th) {
            log.error("[{}] 未捕获异常", TASK_NAME, th);
            EvalTaskResult r = EvalTaskResult.error(TASK_NAME, DISPLAY_NAME,
                    th.getClass().getSimpleName() + ": " + th.getMessage());
            r.setElapsedMs(System.currentTimeMillis() - t0);
            return r;
        }
    }

    private EvalTaskResult doRun(long t0) {
        // ---- 1. 加载数据集 ----
        List<IntentEvalCase> cases = loadCases();
        if (cases == null || cases.isEmpty()) {
            return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                    "未找到或解析失败: classpath:" + AuditConfig.INTENT_EVAL_SET_RESOURCE
                            + " (Batch 2 是否已交付?)");
        }
        log.info("[{}] 加载到 {} 条 case", TASK_NAME, cases.size());

        // ---- 2. 逐条调主应用 ----
        // perCategory key=category, value=[passCount, totalCount]
        Map<String, int[]> perCategory = new TreeMap<>();
        Map<String, Integer> sourceDist = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        int pass = 0;
        long totalLatency = 0;
        int httpFailures = 0;

        for (IntentEvalCase c : cases) {
            perCategory.computeIfAbsent(c.getCategory(), k -> new int[2])[1]++;

            Map<String, Object> req = new HashMap<>();
            req.put("query", c.getQuery());
            Map<String, Object> resp;
            try {
                resp = http.postJson(INTENT_ENDPOINT, req);
            } catch (IOException e) {
                httpFailures++;
                // 第一次失败直接整体跳过, 主应用没起来时不应该跑出"全 fail"的假象
                if (httpFailures == 1 && pass == 0) {
                    log.warn("[{}] 主应用连通性失败 (第 1 条 case 即失败), 整体跳过. 错误={}",
                            TASK_NAME, e.getMessage());
                    return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                            "主应用 " + AuditConfig.MAIN_APP_BASE_URL + " 不可达: " + e.getMessage());
                }
                // 后续单点失败计入 fail, 不整体崩
                log.warn("[{}] case #{} HTTP 失败: {}", TASK_NAME, c.getEvalId(), e.getMessage());
                failures.add(String.format("#%d [%s] query='%s' → HTTP_ERROR: %s",
                        c.getEvalId(), c.getCategory(), c.getQuery(),
                        truncate(e.getMessage(), 80)));
                continue;
            }

            String actualIntent = strVal(resp.get("intent"), "default");
            String source = strVal(resp.get("source"), "UNKNOWN");
            sourceDist.merge(source, 1, Integer::sum);
            Object elapsedRaw = resp.get("elapsedMs");
            if (elapsedRaw instanceof Number n) {
                totalLatency += n.longValue();
            }

            if (actualIntent.equalsIgnoreCase(c.getExpectedIntent())) {
                pass++;
                perCategory.get(c.getCategory())[0]++;
            } else {
                failures.add(String.format("#%d [%s] query='%s' expected=%s actual=%s source=%s",
                        c.getEvalId(), c.getCategory(), c.getQuery(),
                        c.getExpectedIntent(), actualIntent, source));
            }
        }

        // ---- 3. 组装结果 ----
        int total = cases.size();
        int fail = total - pass;
        double accuracy = total == 0 ? 0.0 : (double) pass / total;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("accuracy", String.format("%.2f%% (%d/%d)", accuracy * 100, pass, total));
        // per-category
        for (Map.Entry<String, int[]> e : perCategory.entrySet()) {
            int[] arr = e.getValue();
            double catAcc = arr[1] == 0 ? 0.0 : (double) arr[0] / arr[1];
            metrics.put("accuracy[" + e.getKey() + "]",
                    String.format("%.2f%% (%d/%d)", catAcc * 100, arr[0], arr[1]));
        }
        // source 分布: 反映关键词命中率 vs LLM 兜底率, 是有价值的 senior 视角指标
        for (Map.Entry<String, Integer> e : sourceDist.entrySet()) {
            metrics.put("source[" + e.getKey() + "]",
                    String.format("%d (%.1f%%)", e.getValue(),
                            e.getValue() * 100.0 / total));
        }
        if (total > 0) {
            metrics.put("avg_classifier_latency_ms", String.format("%.1f", (double) totalLatency / total));
        }
        if (httpFailures > 0) {
            metrics.put("http_failures", httpFailures);
        }

        String summary;
        if (httpFailures > 0) {
            summary = String.format("意图分类整体准确率 %.2f%% (%d/%d). 注意 %d 条 case HTTP 失败, " +
                    "结果可能不完整.", accuracy * 100, pass, total, httpFailures);
        } else {
            summary = String.format("意图分类整体准确率 %.2f%% (%d/%d). 关键词 vs LLM 分布见上表, " +
                    "可用于判断关键词字典覆盖度是否足够.", accuracy * 100, pass, total);
        }

        return EvalTaskResult.builder()
                .taskName(TASK_NAME)
                .displayName(DISPLAY_NAME)
                .status(EvalTaskResult.Status.SUCCESS)
                .elapsedMs(System.currentTimeMillis() - t0)
                .totalCount(total)
                .passCount(pass)
                .failCount(fail)
                .metrics(metrics)
                .failureDetails(failures)
                .summary(summary)
                .build();
    }

    /**
     * 加载并解析 eval-set-intent.txt. 找不到返回 null (run() 据此返回 SKIPPED).
     */
    private List<IntentEvalCase> loadCases() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(AuditConfig.INTENT_EVAL_SET_RESOURCE)) {
            if (is == null) {
                log.warn("[{}] classpath:{} 不存在", TASK_NAME, AuditConfig.INTENT_EVAL_SET_RESOURCE);
                return null;
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new IntentEvalSetParser().parse(content);
        } catch (IOException e) {
            log.warn("[{}] 加载 {} 失败", TASK_NAME, AuditConfig.INTENT_EVAL_SET_RESOURCE, e);
            return null;
        }
    }

    private static String strVal(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
