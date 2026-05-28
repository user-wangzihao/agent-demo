package com.wzh.service;

import com.wzh.entity.dto.dashboard.KpiSnapshot;
import com.wzh.entity.dto.dashboard.TimelineItem;
import com.wzh.mapper.DashboardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大屏数据编排服务 (B4 引入).
 *
 * <p><b>定位</b>: 把 5 个 KPI 的取数过程组装在一处. 业务库走 DashboardMapper,
 * Prometheus 走 PrometheusQueryClient. Controller 只调本类的两个方法, 完全不暴露
 * 数据源差异.</p>
 *
 * <p><b>容错策略</b>: 每个 KPI 取数独立 try-catch, 单个失败不波及其他 KPI.
 * 失败时该 KPI 字段为 0/默认值, 前端按 0 渲染 (大屏可能显示 "--").
 * 这样设计的代价是: 大屏可能"局部失明" — 但全屏崩溃比局部失明严重得多.</p>
 *
 * <p><b>性能预算</b>:
 * <ul>
 *   <li>5-6 条 SQL 总耗时 &lt; 50ms (本地 MySQL)</li>
 *   <li>2-3 条 Prometheus query 总耗时 &lt; 200ms (本地 Prometheus)</li>
 *   <li>Service 整体 &lt; 300ms, 前端 30s 轮询完全无压力</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-22 (B4)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardMapper dashboardMapper;
    private final PrometheusQueryClient prometheus;
    private final TicketStatsClient ticketStatsClient;

    /**
     * 取一次 KPI 快照 (5 卡数据).
     *
     * <p>所有取数串行, 因为单次总耗时已经在预算内, 并行只是徒增复杂度.</p>
     */
    public KpiSnapshot getKpiSnapshot() {
        long start = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate yesterday = today.minusDays(1);

        Map<String, Long> debug = new LinkedHashMap<>();
        KpiSnapshot.KpiSnapshotBuilder builder = KpiSnapshot.builder()
                .generatedAt(LocalDateTime.now());

        // ==================== MySQL 数据 (卡 1, 3, 5) ====================

        long mysqlStart = System.currentTimeMillis();

        // 卡 1: 今日对话
        builder.todayChatCount(safeLong(() ->
                dashboardMapper.countUserMessagesInRange(today, tomorrow), "todayChatCount"));
        builder.todayActiveUserCount(safeLong(() ->
                dashboardMapper.countActiveUsersInRange(today, tomorrow), "todayActiveUserCount"));

        // 卡 3: FAQ 命中率 (今日 + 昨日 delta)
        long todayUser = builder.build().getTodayChatCount();  // 复用已查的 today user 计数
        long todayHit = safeLong(() ->
                dashboardMapper.countFaqHitInRange(today, tomorrow), "todayFaqHit");
        double todayRate = (todayUser == 0) ? 0.0 : (todayHit * 100.0 / todayUser);

        long yesterdayUser = safeLong(() ->
                dashboardMapper.countUserMessagesInRange(yesterday, today), "yesterdayChatCount");
        long yesterdayHit = safeLong(() ->
                dashboardMapper.countFaqHitInRange(yesterday, today), "yesterdayFaqHit");
        double yesterdayRate = (yesterdayUser == 0) ? 0.0 : (yesterdayHit * 100.0 / yesterdayUser);

        builder.faqHitRate(round2(todayRate));
        builder.faqHitRateDeltaVsYesterday(round2(todayRate - yesterdayRate));

        // 卡 5: 飞轮 + 工单
        builder.faqCandidatePending(safeLong(
                dashboardMapper::countFaqCandidatePending, "faqCandidatePending"));

        long todayCandidate = safeLong(() ->
                dashboardMapper.countFaqCandidateInRange(today, tomorrow), "todayCandidate");
        long yesterdayCandidate = safeLong(() ->
                dashboardMapper.countFaqCandidateInRange(yesterday, today), "yesterdayCandidate");
        builder.faqCandidateDeltaVsYesterday(todayCandidate - yesterdayCandidate);

        builder.todayTicketCount(safeLong(
                ticketStatsClient::getTodayCount, "todayTickets"));

        long mysqlMs = System.currentTimeMillis() - mysqlStart;
        debug.put("mysql", mysqlMs);

        // ==================== Prometheus 数据 (卡 2, 4) ====================

        long promStart = System.currentTimeMillis();

        // 卡 2: Graph 总耗时 P50/P95
        // 用 finalize 节点的耗时作为端到端总耗时 proxy. finalize 是 graph 最后一个节点,
        // 它的执行时刻 ≈ 整轮对话结束时刻 (finalize 自身只跑约 1-10ms).
        // 用 5m 窗口的 rate 算分位数, 大屏看的是"近期稳定状态" 不是某一次的极值.
        builder.graphLatencyP50Ms(promToMillis(prometheus.queryScalar(
                "histogram_quantile(0.50, sum by (le) (increase(agent_graph_node_latency_seconds_bucket{node_name=\"knowledge_answer\"}[24h])))")));
        builder.graphLatencyP95Ms(promToMillis(prometheus.queryScalar(
                "histogram_quantile(0.95, sum by (le) (increase(agent_graph_node_latency_seconds_bucket{node_name=\"knowledge_answer\"}[24h])))")));

        // 卡 4: 今日 Token 总量 + 占比最高的 scene
        // increase(...[24h]) 算 24 小时增量, sum 后只剩一个标量.
        Double totalToken = prometheus.queryScalar(
                "sum(increase(agent_llm_tokens_total{token_type=\"total\"}[24h]))");
        builder.todayTokenTotal(totalToken == null ? 0L : totalToken.longValue());

        // 按 scene 聚合, 找占比最高的 scene
        Map<String, Double> byScene = prometheus.queryGrouped(
                "sum by (scene) (increase(agent_llm_tokens_total{token_type=\"total\"}[24h]))",
                "scene");
        String topScene = "unknown";
        double topScenePercent = 0.0;
        if (!byScene.isEmpty() && totalToken != null && totalToken > 0) {
            double maxValue = 0.0;
            for (Map.Entry<String, Double> e : byScene.entrySet()) {
                if (e.getValue() != null && e.getValue() > maxValue) {
                    maxValue = e.getValue();
                    topScene = e.getKey();
                }
            }
            topScenePercent = round2(maxValue * 100.0 / totalToken);
        }
        builder.topTokenScene(topScene);
        builder.topTokenScenePercent(topScenePercent);

        // ==================== 卡 6: 缓存命中率 (B6) ====================
        // 故意拆成 3 个 scalar 查 (而不是一次 PromQL 算好 hitRate), 因为前端副数字要展示
        // L1/L2 分布. 拆开后端组装, 比合并查询更清晰.
        Double cacheHitL1 = prometheus.queryScalar(
                "sum(increase(agent_cache_hit_total{layer=\"L1\"}[24h]))");
        Double cacheHitL2 = prometheus.queryScalar(
                "sum(increase(agent_cache_hit_total{layer=\"L2\"}[24h]))");
        Double cacheMiss = prometheus.queryScalar(
                "sum(increase(agent_cache_miss_total[24h]))");
        long l1 = cacheHitL1 == null ? 0L : cacheHitL1.longValue();
        long l2 = cacheHitL2 == null ? 0L : cacheHitL2.longValue();
        long miss = cacheMiss == null ? 0L : cacheMiss.longValue();
        long totalLookup = l1 + l2 + miss;
        double cacheHitRate = totalLookup == 0 ? 0.0
                : round2(((double) (l1 + l2)) * 100.0 / totalLookup);
        builder.cacheHitL1Count(l1);
        builder.cacheHitL2Count(l2);
        builder.cacheMissCount(miss);
        builder.cacheHitRate(cacheHitRate);

        long promMs = System.currentTimeMillis() - promStart;
        debug.put("prometheus", promMs);
        debug.put("total", System.currentTimeMillis() - start);
        builder.debugLatencyMs(debug);

        KpiSnapshot snapshot = builder.build();
        log.info("[DASHBOARD-KPI] generated. chats={} users={} hit={}% tokens={} topScene={}({}%) " +
                        "pending={} tickets={} cacheHit={}%(L1={}+L2={} miss={}) mysqlMs={} promMs={} totalMs={}",
                snapshot.getTodayChatCount(), snapshot.getTodayActiveUserCount(),
                snapshot.getFaqHitRate(), snapshot.getTodayTokenTotal(),
                snapshot.getTopTokenScene(), snapshot.getTopTokenScenePercent(),
                snapshot.getFaqCandidatePending(), snapshot.getTodayTicketCount(),
                snapshot.getCacheHitRate(), snapshot.getCacheHitL1Count(),
                snapshot.getCacheHitL2Count(), snapshot.getCacheMissCount(),
                mysqlMs, promMs, debug.get("total"));
        return snapshot;
    }

    /**
     * 取滚动时间线 (最近 N 条对话摘要).
     *
     * @param limit 限制条数, 大屏一般 15-20 条, 上限 50 防滥用
     */
    public List<TimelineItem> getTimeline(int limit) {
        if (limit <= 0) limit = 20;
        if (limit > 50) limit = 50;
        try {
            return dashboardMapper.selectRecentTimeline(limit);
        } catch (Exception e) {
            log.warn("[DASHBOARD-TIMELINE] failed limit={}", limit, e);
            return Collections.emptyList();
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 跑一个 Long 取数 lambda, 任何异常返回 0 (兜底).
     */
    private long safeLong(java.util.function.LongSupplier supplier, String name) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            log.warn("[DASHBOARD] kpi '{}' fetch failed, fallback to 0. err={}",
                    name, e.getMessage());
            return 0L;
        }
    }

    /** Prometheus 返回 seconds, 大屏要 millis. null 兜底 0. */
    private long promToMillis(Double seconds) {
        if (seconds == null || seconds.isNaN() || seconds.isInfinite()) return 0L;
        return Math.round(seconds * 1000);
    }

    /** double 保留 2 位小数. */
    private double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }
}