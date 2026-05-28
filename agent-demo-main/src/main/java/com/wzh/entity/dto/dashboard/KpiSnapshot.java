package com.wzh.entity.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 大屏 5 个 KPI 卡的统一返回结构 (B4 引入).
 *
 * <p><b>设计</b>: 一个端点一次性返回 5 个卡的全部数据, 避免前端发 5 次 HTTP 请求.
 * 前端只需轮询 {@code GET /api/admin/dashboard/kpi} 一个端点, 收到 KpiSnapshot 后
 * 一次刷新 5 个卡片避免撕裂感.</p>
 *
 * <p><b>5 个 KPI 卡分组</b>:
 * <ul>
 *   <li><b>卡 1</b> 今日对话: {@link #todayChatCount} + {@link #todayActiveUserCount}</li>
 *   <li><b>卡 2</b> Graph 耗时: {@link #graphLatencyP50Ms} + {@link #graphLatencyP95Ms} (Prometheus)</li>
 *   <li><b>卡 3</b> FAQ 命中率: {@link #faqHitRate} + {@link #faqHitRateDeltaVsYesterday}</li>
 *   <li><b>卡 4</b> 今日 Token: {@link #todayTokenTotal} + {@link #topTokenScene} (Prometheus)</li>
 *   <li><b>卡 5</b> 飞轮+工单: {@link #faqCandidatePending} + {@link #todayTicketCount}
 *       + {@link #faqCandidateDeltaVsYesterday}</li>
 * </ul></p>
 *
 * <p><b>取值约定</b>:
 * <ul>
 *   <li>所有 Long 字段在数据缺失时返回 0 而非 null, 前端不需处理 null 渲染</li>
 *   <li>所有比率/百分比字段以 double 表示 0-100 区间, 前端按需加 %</li>
 *   <li>delta 字段表示"相比昨日的变化值", 正数=增长 / 负数=下降 / 0=持平</li>
 *   <li>{@link #generatedAt} 标记数据生成时刻, 前端可显示"上次更新于 X 秒前"</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-22 (B4)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiSnapshot {

    // ==================== 卡 1: 今日对话 ====================

    /** 今日 (00:00 ~ now) chat_message 表 role='user' 的总条数. */
    private long todayChatCount;

    /** 今日发起过对话的去重用户数 (count distinct user_id from chat_session today). */
    private long todayActiveUserCount;

    // ==================== 卡 2: Graph 耗时 (来自 Prometheus) ====================

    /**
     * Graph 总耗时 P50 (毫秒). 注意是端到端总耗时 (preprocess 到 finalize), 不是单节点.
     * PromQL: histogram_quantile(0.50, sum by(le) (rate(agent_graph_node_latency_seconds_bucket{node_name="finalize"}[5m])))
     * 用 finalize 节点的耗时作为总耗时 proxy — finalize 跑完意味着一轮对话结束.
     */
    private long graphLatencyP50Ms;

    /** Graph 总耗时 P95 (毫秒). 同 P50 但 quantile 取 0.95. */
    private long graphLatencyP95Ms;

    // ==================== 卡 3: FAQ 命中率 ====================

    /**
     * FAQ 命中率 (今日, 0-100 百分比).
     * 计算: today faq_hit=true 的 user 消息数 / today 总 user 消息数 * 100.
     */
    private double faqHitRate;

    /** 命中率相比昨日的差值 (百分点). 正数 = 今日比昨日高, 负数 = 低. */
    private double faqHitRateDeltaVsYesterday;

    // ==================== 卡 4: 今日 Token (来自 Prometheus) ====================

    /**
     * 今日 token 总消耗 (所有 scene 累加, token_type=total).
     * PromQL: sum(increase(agent_llm_tokens_total{token_type="total"}[24h]))
     */
    private long todayTokenTotal;

    /**
     * 占比最高的 scene 名 + 该 scene 的 token 占今日总量的百分比.
     * 例: scene="chat_main", percent=68.5
     */
    private String topTokenScene;

    /** topTokenScene 的占比 (0-100). */
    private double topTokenScenePercent;

    // ==================== 卡 5: 飞轮 + 工单 ====================

    /** 当前 faq_candidate 表中 status='pending' 的待审条数. */
    private long faqCandidatePending;

    /** 今日新增的 faq_candidate 数 - 昨日新增的 faq_candidate 数 (相对增减). */
    private long faqCandidateDeltaVsYesterday;

    /** 今日 (00:00 ~ now) ticket_system 创建的工单数. */
    private long todayTicketCount;

    // ==================== 卡 6: 语义缓存命中率 (B6, 来自 Prometheus) ====================

    /**
     * 缓存总命中率 (24h, 0-100 百分比).
     * 计算: sum(hit) / (sum(hit) + sum(miss)) * 100, 时间窗口 24h.
     * PromQL: sum(increase(agent_cache_hit_total[24h])) /
     *         (sum(increase(agent_cache_hit_total[24h])) + sum(increase(agent_cache_miss_total[24h]))) * 100
     * 没数据 (24h 内既无 hit 也无 miss) 返回 0.
     */
    private double cacheHitRate;

    /**
     * 24h 内 L1 命中数 (Redis 字面命中). 用作副数字展示, 帮助理解 L1/L2 分布.
     */
    private long cacheHitL1Count;

    /**
     * 24h 内 L2 命中数 (Milvus 语义命中).
     */
    private long cacheHitL2Count;

    /**
     * 24h 内未命中数. cacheHitRate 分母 = L1 + L2 + miss.
     */
    private long cacheMissCount;

    // ==================== 元数据 ====================

    /** 本次快照生成时刻 (服务端时间). 前端可据此显示"上次更新于 X 秒前". */
    private LocalDateTime generatedAt;

    /**
     * 各 KPI 取数耗时 (毫秒), 仅用于后端性能排查, 前端可不展示.
     * 例: {"mysql": 35, "prometheus": 80, "total": 115}
     */
    private java.util.Map<String, Long> debugLatencyMs;
}