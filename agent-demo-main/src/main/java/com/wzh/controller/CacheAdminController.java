package com.wzh.controller;

import com.wzh.common.Result;
import com.wzh.service.SemanticCacheService;
import com.wzh.service.SemanticCacheService.CacheFeatureStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语义缓存管理端点 (B6 方案 D 冷路径).
 *
 * <p><b>定位</b>: 跟 Grafana 的"缓存命中率"卡是互补关系 — Grafana 看全局聚合 (热路径,
 * 4 个时序),本控制器看按 feature 拆分的细粒度 (冷路径, SQL 直查). 设计原因详见
 * {@link com.wzh.graph.support.GraphMetricsCollector} D 节注释 (high-cardinality 处理).</p>
 *
 * <p><b>权限</b>: 路径前缀 {@code /api/admin/cache/**}, 由 {@code AuthInterceptor#isAdminOnly}
 * 自动锁 admin role (跟 DashboardController 同模式).</p>
 *
 * @author wzh
 * @since 2026-05-27 (第3刀 B6)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
public class CacheAdminController {

    private final SemanticCacheService semanticCacheService;

    /**
     * 按 feature 聚合缓存条目. 大屏"缓存详情"表格的数据源.
     *
     * <p><b>返回排序</b>: totalHits DESC (高命中的 feature 排前面, 便于一眼看到"热门 feature
     * 缓存效果如何").</p>
     *
     * <p><b>前端轮询节奏</b>: 不建议高频. 这是 SQL 全表扫描, 跟 Grafana 实时指标不一样.
     * 建议 60s 调一次, 或者只在用户点"刷新"按钮时拉取.</p>
     */
    @GetMapping("/by-feature")
    public Result<List<CacheFeatureStat>> byFeature() {
        List<CacheFeatureStat> stats = semanticCacheService.aggregateByFeature();
        log.info("[CacheAdmin] by-feature returned {} feature groups", stats.size());
        return Result.success(stats);
    }
}