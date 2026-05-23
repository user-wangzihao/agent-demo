package com.wzh.controller;

import com.wzh.common.Result;
import com.wzh.entity.dto.dashboard.KpiSnapshot;
import com.wzh.entity.dto.dashboard.TimelineItem;
import com.wzh.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大屏数据 API 入口 (B4 引入).
 *
 * <p><b>定位</b>: 大屏前端 (B5 实现) 通过本 Controller 拉取 KPI 快照和滚动时间线.
 * 与 Grafana dashboard 完全独立 — Grafana 给工程师看技术指标, 这两个端点给业务方
 * 看运营快照.</p>
 *
 * <p><b>权限</b>: 路径前缀 {@code /api/admin/dashboard/**}, 由
 * {@link com.wzh.config.AuthInterceptor#isAdminOnly} 自动锁定为 admin role.
 * 普通用户访问会被 403 拦截.</p>
 *
 * @author wzh
 * @since 2026-05-22 (B4)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 取一次 KPI 快照. 5 个卡的数据一次性返回.
     *
     * <p><b>前端轮询节奏</b>: 建议 30s 调一次. 频率再高对 Prometheus 没意义
     * (Prometheus scrape interval 本身就是 15s, 数据新鲜度上限就是 15s).</p>
     */
    @GetMapping("/kpi")
    public Result<KpiSnapshot> getKpi() {
        return Result.success(dashboardService.getKpiSnapshot());
    }

    /**
     * 取最近 N 条对话的滚动时间线.
     *
     * @param limit 默认 20, 上限 50 (Service 层强制限制)
     */
    @GetMapping("/timeline")
    public Result<List<TimelineItem>> getTimeline(
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(dashboardService.getTimeline(limit));
    }
}
