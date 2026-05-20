package com.wzh.agentdemo.evaltools.task;

import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.http.EvalHttpClient;
import com.wzh.agentdemo.evaltools.model.EvalCase;
import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import com.wzh.agentdemo.evaltools.parser.EvalSetParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端到端延迟评估任务 (P50 / P95).
 *
 * <p><b>Batch 6 真实现</b>: 评估器扮演真实用户, 通过 /api/auth/login 拿 token 后
 * 真打主应用 SSE 端点 /api/graph/chat-stream, 测量两个核心指标:
 * <ul>
 *   <li><b>first_token_*</b>: 从请求发出到收到第一个 token event 的时间 (首字时延,
 *       流式产品 UX 的核心指标 — 反映用户"开始看到回复"的等待感)</li>
 *   <li><b>total_*</b>: 从请求发出到收到 done event 的时间 (端到端总时延)</li>
 * </ul>
 *
 * <p><b>评估真实性</b>: 走完整生产路径 (含 AuthInterceptor / UserContext / DB session / history
 * 加载 / Graph stream / SSE 序列化), 数字即用户真感受. 与"裁剪过的内部端点测量"相比,
 * 测量结果直接可用于简历"P50/P95 端到端延迟"陈述, 不需要追加"不含 XX"注释.</p>
 *
 * <p><b>方法学</b>:
 * <ul>
 *   <li>流量样本: eval-set.txt 的 24 条业务 query (混合 intent, 含 chitchat 短路 / 主链路 RAG / ticket).
 *       不分桶, 报告"真实混合流量"的 P50/P95.</li>
 *   <li>每条 query 跑 {@value AuditConfig#LATENCY_TOTAL_RUNS} 次, 前 {@value AuditConfig#LATENCY_WARMUP_RUNS}
 *       次作为 warmup 丢弃 (JVM JIT / Spring 冷启动 / 连接池建立).</li>
 *   <li>串行执行 — 并发会让数字虚低 (线上不会一个用户连发 24 个请求), 评估必须模拟真实负载形态.</li>
 *   <li>单次请求超时 {@value AuditConfig#LATENCY_SSE_TIMEOUT_SEC}s, 超时算 fail 不毁全局.</li>
 * </ul>
 *
 * <p><b>评估期 DB 痕迹</b>: 每次 SSE 请求会在主应用 chat_session/chat_message 表落数据,
 * 全部挂在评估账号 (sys_user.username='user', id=3) 名下. 不构成数据污染问题 —
 * 评估账号天然隔离, 需要时可 DELETE FROM chat_session WHERE user_id=3 清理.</p>
 *
 * @author wzh
 * @since 2026-05-19 (Batch 1 骨架)
 * @since 2026-05-20 (Batch 6 真实现)
 */
@Slf4j
public class LatencyEvalTask implements EvalTask {

    public static final String TASK_NAME = "latency";
    public static final String DISPLAY_NAME = "端到端延迟 (P50 / P95)";

    private static final String SSE_PATH = "/api/graph/chat-stream";

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

        // ---------- 1. 加载评估集 ----------
        List<EvalCase> cases;
        try {
            String text = loadResource(AuditConfig.EVAL_SET_RESOURCE);
            cases = new EvalSetParser().parse(text);
        } catch (Exception e) {
            log.error("[{}] 加载评估集失败: {}", TASK_NAME, e.getMessage(), e);
            return EvalTaskResult.error(TASK_NAME, DISPLAY_NAME,
                    "加载 " + AuditConfig.EVAL_SET_RESOURCE + " 失败: " + e.getMessage());
        }
        if (cases.isEmpty()) {
            return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                    "评估集为空");
        }
        log.info("[{}] 加载 {} 个 case", TASK_NAME, cases.size());

        // ---------- 2. 准备客户端 + 登录拿 token ----------
        EvalHttpClient client = new EvalHttpClient();
        client.setSseReadTimeoutSec(AuditConfig.LATENCY_SSE_TIMEOUT_SEC);

        String token;
        try {
            String user = AuditConfig.resolveEvalUsername();
            String pass = AuditConfig.resolveEvalPassword();
            log.info("[{}] 登录评估账号: {}", TASK_NAME, user);
            token = client.login(user, pass);
            log.info("[{}] 登录成功, token 已获取 (长度={})", TASK_NAME, token.length());
        } catch (IOException e) {
            log.error("[{}] 登录失败: {}", TASK_NAME, e.getMessage());
            return EvalTaskResult.error(TASK_NAME, DISPLAY_NAME,
                    "登录评估账号失败: " + e.getMessage() +
                    " (请确认主应用已启动, 且 sys_user 表有评估账号. 默认账号 user/user123, " +
                    "可通过 EVAL_USER / EVAL_PASSWORD 环境变量覆盖)");
        }

        // ---------- 3. 逐 case × 多轮 跑 SSE, 收集时延样本 ----------
        List<Long> firstTokenMs = new ArrayList<>();
        List<Long> totalMs = new ArrayList<>();
        int totalRuns = 0;
        int passedRuns = 0;
        int failedRuns = 0;
        List<String> failureDetails = new ArrayList<>();

        for (EvalCase ec : cases) {
            log.info("─── evalId={} [{}] query={}",
                    ec.getEvalId(), ec.getFeatureName(), ec.getQuery());

            for (int run = 1; run <= AuditConfig.LATENCY_TOTAL_RUNS; run++) {
                totalRuns++;
                boolean isWarmup = run <= AuditConfig.LATENCY_WARMUP_RUNS;
                String tag = isWarmup ? "warmup" : "measured";

                LatencySample sample;
                try {
                    sample = measureOne(client, token, ec.getQuery());
                } catch (Exception e) {
                    failedRuns++;
                    String msg = String.format(
                            "evalId=%d run=%d/%d (%s) FAIL: %s",
                            ec.getEvalId(), run, AuditConfig.LATENCY_TOTAL_RUNS, tag, e.getMessage());
                    log.warn(msg);
                    failureDetails.add(msg);
                    continue;
                }

                if (isWarmup) {
                    log.info("evalId={} run={} (warmup, 丢弃) firstToken={}ms total={}ms",
                            ec.getEvalId(), run, sample.firstTokenMs, sample.totalMs);
                    continue;
                }

                firstTokenMs.add(sample.firstTokenMs);
                totalMs.add(sample.totalMs);
                passedRuns++;
                log.info("evalId={} run={} firstToken={}ms total={}ms tokens={}",
                        ec.getEvalId(), run, sample.firstTokenMs, sample.totalMs, sample.tokenCount);
            }
        }

        // ---------- 4. 聚合 P50/P95 ----------
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (firstTokenMs.isEmpty()) {
            log.warn("[{}] 没有可用的 measured 样本, 指标无法计算", TASK_NAME);
            metrics.put("samples", 0);
        } else {
            // first_token 系列
            metrics.put("first_token_p50_ms", percentile(firstTokenMs, 0.50));
            metrics.put("first_token_p95_ms", percentile(firstTokenMs, 0.95));
            metrics.put("first_token_avg_ms", avg(firstTokenMs));
            metrics.put("first_token_min_ms", Collections.min(firstTokenMs));
            metrics.put("first_token_max_ms", Collections.max(firstTokenMs));
            // total 系列
            metrics.put("total_p50_ms", percentile(totalMs, 0.50));
            metrics.put("total_p95_ms", percentile(totalMs, 0.95));
            metrics.put("total_avg_ms", avg(totalMs));
            metrics.put("total_min_ms", Collections.min(totalMs));
            metrics.put("total_max_ms", Collections.max(totalMs));
        }
        metrics.put("cases_evaluated", cases.size());
        metrics.put("total_runs", totalRuns);
        metrics.put("warmup_runs_per_case", AuditConfig.LATENCY_WARMUP_RUNS);
        metrics.put("measured_runs_per_case", AuditConfig.LATENCY_TOTAL_RUNS - AuditConfig.LATENCY_WARMUP_RUNS);
        metrics.put("measured_samples", firstTokenMs.size());
        metrics.put("failed_runs", failedRuns);

        String summary = String.format(
                "评估 %d 个 case × %d 轮 (前 %d 轮 warmup 丢弃), 实测样本 %d 条. " +
                "走真实生产路径 (含 AuthInterceptor / DB / Graph / SSE), 数字即用户真实感受. " +
                "first_token 反映流式 UX 首字时延, total 反映端到端总时延.",
                cases.size(),
                AuditConfig.LATENCY_TOTAL_RUNS,
                AuditConfig.LATENCY_WARMUP_RUNS,
                firstTokenMs.size());

        EvalTaskResult.Status status = failedRuns > 0 && firstTokenMs.isEmpty()
                ? EvalTaskResult.Status.ERROR
                : EvalTaskResult.Status.SUCCESS;

        EvalTaskResult result = EvalTaskResult.builder()
                .taskName(TASK_NAME)
                .displayName(DISPLAY_NAME)
                .status(status)
                .elapsedMs(System.currentTimeMillis() - t0)
                .totalCount(firstTokenMs.size())
                .passCount(passedRuns)
                .failCount(failedRuns)
                .metrics(metrics)
                .failureDetails(failureDetails)
                .summary(summary)
                .build();

        log.info("[{}] 完成: first_token P50={} P95={} | total P50={} P95={} ({} 通过 / {} 失败 / {} 样本)",
                TASK_NAME,
                metrics.get("first_token_p50_ms"), metrics.get("first_token_p95_ms"),
                metrics.get("total_p50_ms"), metrics.get("total_p95_ms"),
                passedRuns, failedRuns, firstTokenMs.size());

        return result;
    }

    // ==================== 单次测量 ====================

    /**
     * 单次 SSE 调用的时延样本.
     */
    private static class LatencySample {
        long firstTokenMs;
        long totalMs;
        int tokenCount;
    }

    /**
     * 跑一次 SSE 请求, 返回时延样本.
     * <p>实现要点:
     * <ul>
     *   <li>请求发出前记 t0, 收到第一个 token event 时记 t_first, 收到 done event 时记 t_done</li>
     *   <li>用 AtomicLong 在 callback 闭包里共享变量 (callback 在 OkHttp 调用线程, 但本方法
     *       同步等到 streamSse 返回, 所以单线程语义, AtomicLong 主要用于"final 闭包"语法约束)</li>
     *   <li>error event 直接抛, 由调用方计入 failureDetails</li>
     *   <li>没收到 done 但 stream 自然结束 → 视为不完整, 抛异常</li>
     * </ul>
     */
    private LatencySample measureOne(EvalHttpClient client, String token, String query) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", query);
        // sessionId 不传 → 主应用自动创建新 session (语义对齐"用户首次发问")
        // selectedFeatureName 不传 → 走完整 feature resolve 链路
        // imageUrls 不传 → 纯文本场景

        AtomicLong firstTokenAt = new AtomicLong(-1L);
        AtomicLong doneAt = new AtomicLong(-1L);
        AtomicLong tokenCount = new AtomicLong(0L);
        StringBuilder errorMsg = new StringBuilder();

        long t0 = System.currentTimeMillis();
        client.streamSse(SSE_PATH, payload, token, (eventName, data) -> {
            long now = System.currentTimeMillis();
            switch (eventName) {
                case "token" -> {
                    if (firstTokenAt.get() < 0) {
                        firstTokenAt.set(now);
                    }
                    tokenCount.incrementAndGet();
                }
                case "done" -> doneAt.set(now);
                case "error" -> errorMsg.append(data);
                case "meta" -> { /* 时序上 meta 早于 token, 此场景不关心 meta 内容 */ }
                default -> log.debug("未知 event name: {}", eventName);
            }
        });

        if (errorMsg.length() > 0) {
            throw new IOException("主应用返回 error event: " + errorMsg);
        }
        if (firstTokenAt.get() < 0) {
            throw new IOException("未收到任何 token event (stream 结束但无内容)");
        }
        if (doneAt.get() < 0) {
            throw new IOException("未收到 done event (stream 提前关闭)");
        }

        LatencySample s = new LatencySample();
        s.firstTokenMs = firstTokenAt.get() - t0;
        s.totalMs = doneAt.get() - t0;
        s.tokenCount = (int) tokenCount.get();
        return s;
    }

    // ==================== 统计 ====================

    /**
     * 计算 P-th 百分位 (线性插值法, numpy / scipy 默认实现).
     * 例: percentile([10,20,30,40], 0.5) = 25 (中位数).
     * <p>不使用"取最近秩"方法 — 后者在样本量小时数字跳变剧烈, 评估场景不友好.</p>
     */
    private static long percentile(List<Long> samples, double p) {
        if (samples == null || samples.isEmpty()) return 0L;
        if (p < 0) p = 0;
        if (p > 1) p = 1;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        if (sorted.size() == 1) return sorted.get(0);
        double rank = p * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return sorted.get(low);
        double frac = rank - low;
        return Math.round(sorted.get(low) + frac * (sorted.get(high) - sorted.get(low)));
    }

    private static long avg(List<Long> xs) {
        if (xs == null || xs.isEmpty()) return 0L;
        long sum = 0;
        for (long x : xs) sum += x;
        return sum / xs.size();
    }

    // ==================== 辅助 ====================

    private static String loadResource(String name) throws Exception {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IllegalStateException("classpath 找不到资源: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}