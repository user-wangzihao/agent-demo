package com.wzh.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wzh.agentdemo.common.entity.SemanticCache;
import com.wzh.agentdemo.common.mapper.SemanticCacheMapper;
import com.wzh.config.SemanticCacheProperties;
import com.wzh.service.SemanticCacheMilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 语义缓存过期清理定时任务 (B4).
 *
 * <p><b>触发频率</b>: 每天凌晨 03:00. 与摘要里的策略对齐, 避开业务高峰.</p>
 *
 * <p><b>清理范围</b>: 三件存储中的两件 (MySQL + Milvus), Redis 不清.
 * <ul>
 *   <li><b>MySQL</b>: {@code DELETE FROM semantic_cache WHERE expire_at < now()}.
 *       走 MyBatis-Plus LambdaQueryWrapper, 不引入自定义 SQL.</li>
 *   <li><b>Milvus</b>: 调 {@link SemanticCacheMilvusService#deleteExpired(long)},
 *       按 {@code expire_at_ms < now} 物理删除向量.</li>
 *   <li><b>Redis</b>: 不主动清, 靠 Redis 自带 TTL (写入时已设 ttlHours) 自然过期.
 *       即使 TTL 过期前 MySQL 已删, lookup 链路有 status=ACTIVE 校验兜底,
 *       不会返回脏数据 (DEGRADED/INVALID 都跳过).</li>
 * </ul>
 *
 * <p><b>容错</b>: 两件存储各自 try-catch. MySQL 失败不影响 Milvus 清理 (反之亦然),
 * 因为它们是独立故障域. 失败只 log warn, 下一周期再清.</p>
 *
 * <p><b>幂等性</b>: 清理是按 expire_at < now() 的"窗口", 多次跑结果一致.
 * 不会重复删 (删过的 row 已经不在表里).</p>
 *
 * <p><b>启用条件</b>: 受 {@link SemanticCacheProperties#isEnabled()} 控制. 缓存总开关关掉时,
 * 定时任务直接 noop (没有数据需要清).</p>
 *
 * @author wzh
 * @since 2026-05-26 (第3刀 B4)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticCacheCleanupTask {

    private final SemanticCacheMapper semanticCacheMapper;
    private final SemanticCacheMilvusService semanticCacheMilvusService;
    private final SemanticCacheProperties properties;

    /**
     * 每天凌晨 03:00 执行清理.
     *
     * <p><b>cron 表达式语法</b>: {@code 秒 分 时 日 月 周}. {@code "0 0 3 * * ?"} = 每天 03:00:00.
     * Spring 6 默认时区是服务器 JVM 时区, demo 部署在国内服务器, 即北京时间 03:00.</p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpired() {
        if (!properties.isEnabled()) {
            log.info("[CacheCleanup] disabled, skip");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long nowMs = System.currentTimeMillis();
        log.info("[CacheCleanup] start, now={}", now);

        // Step 1: MySQL — 删除 expire_at < now() 的行.
        // 用 LambdaQueryWrapper 而非自定义 Mapper SQL: 改动收敛在 main 模块,
        // common 模块的 Mapper 不需要新增方法; demo 量级每日过期数据量小 (< 几百行),
        // LambdaQueryWrapper 性能完全够用.
        int mysqlDeleted = 0;
        try {
            LambdaQueryWrapper<SemanticCache> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(SemanticCache::getExpireAt, now);
            mysqlDeleted = semanticCacheMapper.delete(wrapper);
            log.info("[CacheCleanup] MySQL deleted {} expired rows", mysqlDeleted);
        } catch (Exception e) {
            log.warn("[CacheCleanup] MySQL cleanup failed", e);
        }

        // Step 2: Milvus — 删除 expire_at_ms < nowMs 的向量.
        // 完全独立于 Step 1: 即使 MySQL 失败, Milvus 还是要清, 因为它们是独立故障域.
        // SemanticCacheMilvusService.deleteExpired 内部已 try-catch.
        try {
            semanticCacheMilvusService.deleteExpired(nowMs);
            log.info("[CacheCleanup] Milvus deleteExpired triggered with nowMs={}", nowMs);
        } catch (Exception e) {
            log.warn("[CacheCleanup] Milvus cleanup failed", e);
        }

        log.info("[CacheCleanup] done. mysqlDeleted={}", mysqlDeleted);
    }
}