package com.wzh.agentdemo.evaltools;

import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import com.wzh.agentdemo.evaltools.report.UniversalEvalReporter;
import com.wzh.agentdemo.evaltools.task.EvalTask;
import com.wzh.agentdemo.evaltools.task.IntentEvalTask;
import com.wzh.agentdemo.evaltools.task.LatencyEvalTask;
import com.wzh.agentdemo.evaltools.task.RetrievalEvalTask;
import com.wzh.agentdemo.evaltools.task.RouteEvalTask;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估 CI 统一入口 (Batch 1 引入).
 *
 * <p><b>与 {@link GroundTruthAuditor} 的关系</b>: 两者并列存在, 互不影响.
 * <ul>
 *   <li>{@code java -jar eval-tools-*.jar}                   → 跑 GroundTruthAuditor (shade 默认入口)</li>
 *   <li>{@code java -cp eval-tools-*.jar ...EvalRunner --task=all}  → 跑评估 CI 全套</li>
 *   <li>{@code mvn exec:java -Dexec.mainClass=...EvalRunner -Dexec.args="--task=intent"}</li>
 * </ul>
 *
 * <p><b>CLI 参数</b>:
 * <pre>
 *   --task=all       跑全部任务 (默认)
 *   --task=intent    仅意图分类准确率
 *   --task=route     仅路由正确率
 *   --task=retrieval 仅检索质量 MRR/NDCG
 *   --task=latency   仅端到端延迟
 * </pre>
 *
 * <p><b>Batch 1 状态</b>: 框架就绪, 4 个 task 均返回 SKIPPED. 验收标准是
 * {@code --task=all} 能跑通 + 生成报告, 不抛异常.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 1)
 */
@Slf4j
public class EvalRunner {

    private static final String ARG_TASK_PREFIX = "--task=";
    private static final String TASK_ALL = "all";

    public static void main(String[] args) {
        try {
            new EvalRunner().run(args);
        } catch (Exception e) {
            log.error("EvalRunner 顶层异常", e);
            System.exit(1);
        }
    }

    public void run(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        // ---------- 1. 解析 CLI ----------
        String taskFilter = parseTaskArg(args);
        log.info("================ EvalRunner 启动 ================");
        log.info("task filter: {}", taskFilter);

        // ---------- 2. 注册任务 ----------
        // LinkedHashMap 保证迭代顺序与注册顺序一致, 报告里任务顺序可控.
        Map<String, EvalTask> registry = new LinkedHashMap<>();
        register(registry, new IntentEvalTask());
        register(registry, new RouteEvalTask());
        register(registry, new RetrievalEvalTask());
        register(registry, new LatencyEvalTask());

        // ---------- 3. 选择要跑的任务 ----------
        List<EvalTask> toRun = selectTasks(registry, taskFilter);
        if (toRun.isEmpty()) {
            log.error("未匹配到任何任务. 可用 task: {}", String.join(", ", registry.keySet()));
            log.error("示例: --task=all 或 --task=intent");
            System.exit(2);
            return;
        }
        log.info("将执行 {} 个任务: {}", toRun.size(),
                toRun.stream().map(EvalTask::name).collect(Collectors.joining(", ")));

        // ---------- 4. 顺序执行 ----------
        List<EvalTaskResult> results = new ArrayList<>();
        for (EvalTask task : toRun) {
            log.info("─────────── 执行任务: {} ({}) ───────────", task.displayName(), task.name());
            long taskStart = System.currentTimeMillis();
            EvalTaskResult r;
            try {
                r = task.run();
                if (r == null) {
                    // 任务实现违约, 兜底为 ERROR
                    r = EvalTaskResult.error(task.name(), task.displayName(),
                            "任务返回 null, 违反 EvalTask 契约");
                }
            } catch (Throwable th) {
                // 严格说 EvalTask.run 不应抛异常, 但兜底防御
                log.error("任务 {} 抛出未捕获异常", task.name(), th);
                r = EvalTaskResult.error(task.name(), task.displayName(),
                        th.getClass().getSimpleName() + ": " + th.getMessage());
            }
            if (r.getElapsedMs() == 0L) {
                r.setElapsedMs(System.currentTimeMillis() - taskStart);
            }
            results.add(r);
            log.info("任务 {} 完成: status={} elapsed={}ms",
                    task.name(), r.getStatus(), r.getElapsedMs());
        }

        // ---------- 5. 出报告 ----------
        UniversalEvalReporter reporter = new UniversalEvalReporter();
        Path reportPath = reporter.write(results);

        long elapsed = System.currentTimeMillis() - t0;
        log.info("================ EvalRunner 完成 ================");
        log.info("总耗时: {} ms", elapsed);
        log.info("报告路径: {}", reportPath.toAbsolutePath());
        long success = results.stream().filter(r -> r.getStatus() == EvalTaskResult.Status.SUCCESS).count();
        long skipped = results.stream().filter(r -> r.getStatus() == EvalTaskResult.Status.SKIPPED).count();
        long error = results.stream().filter(r -> r.getStatus() == EvalTaskResult.Status.ERROR).count();
        log.info("状态分布: SUCCESS={} SKIPPED={} ERROR={}", success, skipped, error);
    }

    // ==================== 内部辅助 ====================

    private void register(Map<String, EvalTask> registry, EvalTask task) {
        registry.put(task.name(), task);
    }

    /**
     * 解析 {@code --task=xxx} 参数, 缺省返回 "all".
     * <p>多个 --task= 时取最后一个 (符合 CLI 惯例). 未识别的参数静默忽略.</p>
     */
    private String parseTaskArg(String[] args) {
        String task = TASK_ALL;
        if (args == null) return task;
        for (String a : args) {
            if (a == null) continue;
            String trimmed = a.trim();
            if (trimmed.startsWith(ARG_TASK_PREFIX)) {
                String v = trimmed.substring(ARG_TASK_PREFIX.length()).trim();
                if (!v.isEmpty()) task = v;
            }
        }
        return task;
    }

    /**
     * 根据 filter 选择要执行的任务.
     * <p>filter=all → 全部; 其他 → 取 registry 中 name 严格等于 filter 的那个.</p>
     */
    private List<EvalTask> selectTasks(Map<String, EvalTask> registry, String filter) {
        if (TASK_ALL.equalsIgnoreCase(filter)) {
            return new ArrayList<>(registry.values());
        }
        EvalTask hit = registry.get(filter);
        return hit == null ? new ArrayList<>() : List.of(hit);
    }
}
