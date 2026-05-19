package com.wzh.agentdemo.evaltools.task;

import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import com.wzh.agentdemo.evaltools.model.IntentEvalCase;
import com.wzh.agentdemo.evaltools.parser.IntentEvalSetParser;
import com.wzh.agentdemo.evaltools.route.RouteSimulator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 路由正确率评估任务 (评估 CI 主线 Batch 4 真实现).
 *
 * <p><b>定位</b>: 与 {@link IntentEvalTask} 解耦, 评估的是 <b>纯路由逻辑</b> —— "假设
 * 意图分类完全正确, RouteUtil + MainGraphConfig 的分流出口对不对?"</p>
 *
 * <h2>设计决策: 输入 expectedIntent 而非实际跑出来的 intent</h2>
 *
 * <p>本任务把每条 case 的 {@code expectedIntent} 作为输入喂给 {@link RouteSimulator},
 * 而不是真去调主应用拿 actualIntent. 这样设计的理由:
 * <ul>
 *   <li><b>独立可观测</b>: 路由的正确性不被意图分类的 bug 污染. 即使 IntentEvalTask
 *       跑出 85.71%, RouteEvalTask 也能独立评估"路由层是否始终把指定意图带到对的出口".</li>
 *   <li><b>无网络依赖</b>: 0 HTTP 调用, 不需要起主应用, 跑得快.</li>
 *   <li><b>变更保护</b>: 主要价值不在数字 (大概率接近 100%), 而是<b>未来重构 RouteUtil
 *       或 MainGraphConfig 时</b>能保证既有路由规则不被破坏 (回归测试性质).</li>
 * </ul>
 *
 * <h2>评估流程</h2>
 *
 * <ol>
 *   <li>加载 {@code eval-set-intent.txt}, 解析为 IntentEvalCase 列表</li>
 *   <li>跳过没标 expected_route 的 case (parser 容忍该字段缺失)</li>
 *   <li>对每条 case, 调 {@code RouteSimulator.simulate(expectedIntent, query, userRole)}</li>
 *   <li>与 expectedRoute 严格比对; 不一致计入失败用例</li>
 *   <li>输出 overall accuracy + per-category accuracy + per-route accuracy + 失败用例列表</li>
 * </ol>
 *
 * <h2>per-route accuracy 的意义</h2>
 *
 * <p>不仅看整体, 还按 expected_route 分组看每类出口的命中率. 这能定位:
 * <ul>
 *   <li>chitchat_answer accuracy 低 → chitchat 短路判定可能漏掉某些意图</li>
 *   <li>admin_agent accuracy 低 → admin 短路条件或 userRole 解析有问题</li>
 *   <li>ticket_agent accuracy 低 → TICKET_PATTERN 正则未覆盖某些工单措辞</li>
 *   <li>feature_resolve accuracy 低 → admin_command + 非 admin 降级链路被破坏</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 4 真实现; Batch 1 骨架替换)
 */
@Slf4j
public class RouteEvalTask implements EvalTask {

    public static final String TASK_NAME = "route";
    public static final String DISPLAY_NAME = "路由正确率";

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
        List<IntentEvalCase> allCases = loadCases();
        if (allCases == null || allCases.isEmpty()) {
            return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                    "未找到或解析失败: classpath:" + AuditConfig.INTENT_EVAL_SET_RESOURCE);
        }

        // ---- 2. 过滤出标了 expected_route 的 case ----
        List<IntentEvalCase> cases = new ArrayList<>();
        int skippedNoRoute = 0;
        for (IntentEvalCase c : allCases) {
            if (c.getExpectedRoute() == null || c.getExpectedRoute().isBlank()) {
                skippedNoRoute++;
            } else {
                cases.add(c);
            }
        }
        if (cases.isEmpty()) {
            return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                    "数据集中无任何 case 标注了 expected_route (共 " + allCases.size() + " 条 case)");
        }
        log.info("[{}] 加载 {} 条 case, 其中 {} 条无 expected_route 跳过, 实际评估 {} 条",
                TASK_NAME, allCases.size(), skippedNoRoute, cases.size());

        // ---- 3. 跑模拟器, 对比 ----
        Map<String, int[]> perCategory = new TreeMap<>(); // [pass, total]
        Map<String, int[]> perExpectedRoute = new TreeMap<>(); // [pass, total]
        List<String> failures = new ArrayList<>();
        int pass = 0;

        for (IntentEvalCase c : cases) {
            perCategory.computeIfAbsent(c.getCategory(), k -> new int[2])[1]++;
            perExpectedRoute.computeIfAbsent(c.getExpectedRoute(), k -> new int[2])[1]++;

            String actualRoute = RouteSimulator.simulate(
                    c.getExpectedIntent(), c.getQuery(), c.getUserRole());

            if (actualRoute.equals(c.getExpectedRoute())) {
                pass++;
                perCategory.get(c.getCategory())[0]++;
                perExpectedRoute.get(c.getExpectedRoute())[0]++;
            } else {
                failures.add(String.format(
                        "#%d [%s|role=%s] intent=%s query='%s' expected=%s actual=%s",
                        c.getEvalId(), c.getCategory(), c.getUserRole(),
                        c.getExpectedIntent(), c.getQuery(),
                        c.getExpectedRoute(), actualRoute));
            }
        }

        // ---- 4. 组装指标 ----
        int total = cases.size();
        int fail = total - pass;
        double accuracy = total == 0 ? 0.0 : (double) pass / total;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("accuracy", String.format("%.2f%% (%d/%d)", accuracy * 100, pass, total));
        if (skippedNoRoute > 0) {
            metrics.put("skipped_no_expected_route", skippedNoRoute);
        }
        // per-category accuracy
        for (Map.Entry<String, int[]> e : perCategory.entrySet()) {
            int[] arr = e.getValue();
            double cat = arr[1] == 0 ? 0.0 : (double) arr[0] / arr[1];
            metrics.put("accuracy[" + e.getKey() + "]",
                    String.format("%.2f%% (%d/%d)", cat * 100, arr[0], arr[1]));
        }
        // per-expected-route accuracy: 定位是哪条路由规则有问题
        for (Map.Entry<String, int[]> e : perExpectedRoute.entrySet()) {
            int[] arr = e.getValue();
            double r = arr[1] == 0 ? 0.0 : (double) arr[0] / arr[1];
            metrics.put("route[" + e.getKey() + "]",
                    String.format("%.2f%% (%d/%d)", r * 100, arr[0], arr[1]));
        }

        String summary = String.format(
                "路由正确率 %.2f%% (%d/%d). 输入用 expectedIntent (而非实际意图分类结果), " +
                        "评估的是纯路由逻辑. 此任务无网络依赖, 主要价值是回归保护 — " +
                        "未来重构 RouteUtil / MainGraphConfig 时可保证既有规则不破.",
                accuracy * 100, pass, total);

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
}
