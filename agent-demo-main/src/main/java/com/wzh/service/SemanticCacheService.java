package com.wzh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.CacheStatus;
import com.wzh.agentdemo.common.entity.SemanticCache;
import com.wzh.agentdemo.common.mapper.SemanticCacheMapper;
import com.wzh.common.CacheEntry;
import com.wzh.config.SemanticCacheProperties;
import com.wzh.enums.Intent;
import com.wzh.graph.support.SourceInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语义缓存对外门面 (第3刀).
 *
 * <p>统筹 MySQL (semantic_cache 表) + Redis (cache:answer:*) + Milvus (semantic_cache_vectors)
 * 三件存储, 对 CacheCheckNode / FinalizeNode / FeedbackService 等调用方暴露简洁 API.</p>
 *
 * <p><b>写入顺序</b>: MySQL → Redis → Milvus. 任何一步失败仅 warn 日志, 不回滚 (尽力而为).</p>
 *
 * <p><b>命中链路</b>: L1 直接 MD5 算 cacheKey 查 Redis → L1 miss 才向量化走 L2 ANN.
 * 命中后必须查 MySQL 校验 status=ACTIVE 才算真命中, DEGRADED/INVALID 降级 miss.</p>
 */
@Slf4j
@Service
public class SemanticCacheService {

    private static final String REDIS_KEY_PREFIX = "cache:answer:";

    @Autowired
    private SemanticCacheProperties properties;

    @Autowired
    private SemanticCacheMapper semanticCacheMapper;

    @Autowired
    private SemanticCacheMilvusService milvusService;

    @Autowired
    private DashScopeService dashScopeService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * B6: degrade 事件埋点. 不暴露 feature_name 标签 (高基数), 仅做全局计数.
     * 按 feature 的 degrade 分布走 SQL 直查 semantic_cache 表 status=DEGRADED 的行.
     */
    @Autowired
    private com.wzh.graph.support.GraphMetricsCollector graphMetricsCollector;

    // ==================== 公共 API ====================

    /**
     * 查询缓存. L1 → L2 顺序尝试.
     *
     * <p>命中条件:</p>
     * <ol>
     *   <li>L1: cacheKey = MD5(featureName + normalize(query)), 直接查 Redis</li>
     *   <li>L2: query 向量化 → Milvus ANN (按 featureName filter, 余弦 ≥ threshold) → 拿 cacheKey 查 Redis</li>
     *   <li>命中后必须查 MySQL 校验 status=ACTIVE, 否则降级 miss</li>
     * </ol>
     *
     * <p>命中且校验通过时: 同步累加 hit_count + last_hit_time.</p>
     *
     * @return 永不返回 null. 未命中时 hit=false.
     */
    public CacheLookupResult lookup(String query, String featureName, Intent intent) {
        if (!properties.isEnabled() || query == null || query.isBlank() || featureName == null) {
            return CacheLookupResult.miss();
        }

        // L1 精确命中
        String l1Key = computeCacheKey(featureName, intent, query);
        CacheEntry l1Entry = readRedis(l1Key);
        if (l1Entry != null && validateStatus(l1Key)) {
            incrementHitAsync(l1Key);
            return CacheLookupResult.hitL1(l1Key, l1Entry);
        }

        // L2 语义命中
        try {
            List<Float> queryEmbedding = dashScopeService.getEmbedding(query);
            SemanticCacheMilvusService.Hit hit = milvusService.searchTopOne(
                    queryEmbedding, featureName, properties.getSimilarityThreshold());
            if (hit != null) {
                CacheEntry l2Entry = readRedis(hit.getCacheKey());
                if (l2Entry != null && validateStatus(hit.getCacheKey())) {
                    incrementHitAsync(hit.getCacheKey());
                    return CacheLookupResult.hitL2(hit.getCacheKey(), l2Entry, hit.getSimilarity());
                }
            }
        } catch (Exception e) {
            log.warn("[SemanticCache] L2 lookup failed query={} featureName={}", query, featureName, e);
        }
        return CacheLookupResult.miss();
    }

    /**
     * 写入缓存. 顺序 MySQL → Redis → Milvus, 任意失败仅 warn.
     *
     * <p><b>upsert 策略</b>: 同 cacheKey 已存在的情况:
     * <ul>
     *   <li>status=ACTIVE: 理论上不应发生 (说明上游没正确做 lookup), 仅 warn, 不重复写</li>
     *   <li>status=DEGRADED/INVALID: UPDATE 复活 — status=ACTIVE, answer_text=新答案,
     *       feedback_score=0, hit_count=0, expire_at=新过期; Redis/Milvus 重建. 这是 DEGRADED 后
     *       重新生成的合法链路.</li>
     * </ul></p>
     *
     * @return 写入的 cacheKey (即使部分失败也返回, 调用方据此写 chat_message.cache_key)
     */
    public String put(String query, String featureName, Intent intent,
                      String answerText, List<SourceInfo> sources, List<String> relatedImages) {
        if (!properties.isEnabled()) return null;
        if (query == null || query.isBlank() || featureName == null || answerText == null) return null;

        String cacheKey = computeCacheKey(featureName, intent, query);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plusHours(properties.getTtlHours());

        // Step 1: MySQL upsert
        try {
            SemanticCache existing = semanticCacheMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SemanticCache>()
                            .eq(SemanticCache::getCacheKey, cacheKey));
            if (existing == null) {
                // INSERT
                SemanticCache row = new SemanticCache();
                row.setCacheKey(cacheKey);
                row.setFeatureName(featureName);
                row.setQueryText(query);
                row.setAnswerText(answerText);
                row.setSourceInfo(sources == null ? null : safeToJson(sources));
                row.setStatus(CacheStatus.ACTIVE);
                row.setHitCount(0);
                row.setFeedbackScore(0);
                row.setExpireAt(expireAt);
                semanticCacheMapper.insert(row);
            } else if (CacheStatus.ACTIVE.equals(existing.getStatus())) {
                // 已有 ACTIVE 记录: 说明 lookup 应已命中, 不应重复 put. 仅 warn 并跳过.
                log.warn("[SemanticCache] put on existing ACTIVE record, skip cacheKey={}", cacheKey);
                return cacheKey;
            } else {
                // DEGRADED / INVALID: 复活
                SemanticCache update = new SemanticCache();
                update.setCacheKey(cacheKey);   // 仅供 wrapper 定位
                update.setFeatureName(featureName);
                update.setQueryText(query);
                update.setAnswerText(answerText);
                update.setSourceInfo(sources == null ? null : safeToJson(sources));
                update.setStatus(CacheStatus.ACTIVE);
                update.setHitCount(0);
                update.setFeedbackScore(0);
                update.setExpireAt(expireAt);
                semanticCacheMapper.update(update,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SemanticCache>()
                                .eq(SemanticCache::getCacheKey, cacheKey));
                log.info("[SemanticCache] revived cacheKey={} (was status={})", cacheKey, existing.getStatus());
            }
        } catch (Exception e) {
            log.warn("[SemanticCache] MySQL upsert failed cacheKey={}, skip Redis/Milvus", cacheKey, e);
            return null;
        }

        // Step 2: Redis
        CacheEntry entry = new CacheEntry(cacheKey, featureName, answerText, sources,
                relatedImages == null ? java.util.Collections.emptyList() : relatedImages,
                System.currentTimeMillis());
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + cacheKey, json,
                    Duration.ofHours(properties.getTtlHours()));
        } catch (Exception e) {
            log.warn("[SemanticCache] Redis set failed cacheKey={}, continue to Milvus", cacheKey, e);
        }

        // Step 3: Milvus (复活场景下也要先删旧的向量, 避免主键冲突)
        try {
            milvusService.deleteByCacheKey(cacheKey);   // 幂等; 不存在也不报错
            List<Float> embedding = dashScopeService.getEmbedding(query);
            long expireAtMs = expireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            milvusService.insert(cacheKey, featureName, embedding, expireAtMs);
        } catch (Exception e) {
            log.warn("[SemanticCache] Milvus insert failed cacheKey={} (MySQL+Redis already written)",
                    cacheKey, e);
        }

        return cacheKey;
    }

    /**
     * 负反馈累加. 同时检查是否达阈值, 达则置 DEGRADED + 清 Redis + 清 Milvus.
     *
     * @param weight 加权分 (点踩=2 / 重新生成=1 / 工单=3)
     */
    public void incrementFeedback(String cacheKey, int weight) {
        if (!properties.isEnabled() || cacheKey == null || cacheKey.isBlank() || weight <= 0) return;
        try {
            int affected = semanticCacheMapper.incrementFeedbackScore(cacheKey, weight);
            if (affected == 0) {
                log.debug("[SemanticCache] feedback target not found cacheKey={}", cacheKey);
                return;
            }
            SemanticCache row = semanticCacheMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SemanticCache>()
                            .eq(SemanticCache::getCacheKey, cacheKey));
            if (row == null) return;
            if (CacheStatus.ACTIVE.equals(row.getStatus())
                    && row.getFeedbackScore() != null
                    && row.getFeedbackScore() >= properties.getFeedbackThreshold()) {
                degrade(cacheKey);
            }
        } catch (Exception e) {
            log.warn("[SemanticCache] incrementFeedback failed cacheKey={}", cacheKey, e);
        }
    }

    /**
     * 按 featureName 批量失效 (管理员重学文档 / 操作 FAQ 时调用).
     *
     * <p>三件存储联动:</p>
     * <ol>
     *   <li>MySQL: 所有该 featureName 的记录置 INVALID</li>
     *   <li>Milvus: 删该 featureName 所有向量</li>
     *   <li>Redis: 当前实现无法按 featureName 批量删 Redis key (无前缀关联),
     *       依赖 TTL 自然过期 + 命中时校验 status=INVALID 降级 miss 兜底.</li>
     * </ol>
     */
    public void invalidateByFeatureName(String featureName) {
        if (!properties.isEnabled() || featureName == null || featureName.isBlank()) return;
        try {
            int affected = semanticCacheMapper.invalidateByFeatureName(featureName);
            log.info("[SemanticCache] invalidate by feature {} affected {} rows", featureName, affected);
            milvusService.deleteByFeatureName(featureName);
        } catch (Exception e) {
            log.warn("[SemanticCache] invalidateByFeatureName failed featureName={}", featureName, e);
        }
    }

    /**
     * 按 feature 维度聚合缓存条目, 供大屏"缓存详情"表格展示 (B6 方案 D 冷路径).
     *
     * <p><b>设计动机</b>: Prometheus 不暴露 feature_name 标签 (700+ feature 会导致基数爆炸,
     * 详见 GraphMetricsCollector D 节注释). 按 feature 的细粒度分析走 SQL 直查,
     * 因为 semantic_cache 表本身已经有 hit_count / feedback_score / status 字段,
     * 数据比 Prometheus 时序还精确 (Prometheus 重启会丢历史 Counter).</p>
     *
     * <p><b>实现策略</b>: 全表 SELECT + 内存 group by. 选这个而非自定义 SQL GROUP BY 的原因:
     * <ul>
     *   <li>改动收敛在 main 模块, 不动 common 模块的 SemanticCacheMapper</li>
     *   <li>demo / 中型企业量级 (< 5000 条) 性能完全足够, 毫秒级</li>
     *   <li>未来超大规模再换自定义 SQL, 接口契约不变</li>
     * </ul></p>
     *
     * @return 按 totalHits DESC 排序的 list, 空表返回空 list 而非 null
     */
    public List<CacheFeatureStat> aggregateByFeature() {
        if (!properties.isEnabled()) return List.of();
        try {
            List<SemanticCache> all = semanticCacheMapper.selectList(null);
            if (all == null || all.isEmpty()) return List.of();

            // 按 featureName 分组聚合
            Map<String, List<SemanticCache>> grouped = all.stream()
                    .filter(r -> r.getFeatureName() != null && !r.getFeatureName().isBlank())
                    .collect(Collectors.groupingBy(SemanticCache::getFeatureName));

            return grouped.entrySet().stream()
                    .map(e -> buildStat(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingLong(CacheFeatureStat::getTotalHits).reversed())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[SemanticCache] aggregateByFeature failed", e);
            return List.of();
        }
    }

    /** 把同 featureName 的 rows 聚合成 CacheFeatureStat. */
    private CacheFeatureStat buildStat(String featureName, List<SemanticCache> rows) {
        CacheFeatureStat stat = new CacheFeatureStat();
        stat.setFeatureName(featureName);
        stat.setTotalEntries(rows.size());
        long totalHits = 0;
        long totalFeedback = 0;
        int activeCount = 0;
        int degradedCount = 0;
        int invalidCount = 0;
        for (SemanticCache r : rows) {
            if (r.getHitCount() != null) totalHits += r.getHitCount();
            if (r.getFeedbackScore() != null) totalFeedback += r.getFeedbackScore();
            String s = r.getStatus();
            if (CacheStatus.ACTIVE.equals(s)) activeCount++;
            else if (CacheStatus.DEGRADED.equals(s)) degradedCount++;
            else if (CacheStatus.INVALID.equals(s)) invalidCount++;
        }
        stat.setTotalHits(totalHits);
        stat.setTotalFeedbackScore(totalFeedback);
        stat.setActiveCount(activeCount);
        stat.setDegradedCount(degradedCount);
        stat.setInvalidCount(invalidCount);
        return stat;
    }

    // ==================== 内部 ====================

    /** 校验 MySQL 中 status 是否 ACTIVE. 非 ACTIVE (DEGRADED/INVALID) 返回 false. */
    private boolean validateStatus(String cacheKey) {
        try {
            SemanticCache row = semanticCacheMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SemanticCache>()
                            .eq(SemanticCache::getCacheKey, cacheKey)
                            .select(SemanticCache::getStatus));
            return row != null && CacheStatus.ACTIVE.equals(row.getStatus());
        } catch (Exception e) {
            log.warn("[SemanticCache] validateStatus failed cacheKey={}", cacheKey, e);
            return false;
        }
    }

    private CacheEntry readRedis(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + cacheKey);
            if (json == null) return null;
            return objectMapper.readValue(json, CacheEntry.class);
        } catch (Exception e) {
            log.warn("[SemanticCache] readRedis failed cacheKey={}", cacheKey, e);
            return null;
        }
    }

    private void incrementHitAsync(String cacheKey) {
        // 同步执行 (代价小, MyBatis-Plus update 单行); 想异步可加 @Async, 这里保持简单
        try {
            semanticCacheMapper.incrementHitCount(cacheKey, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[SemanticCache] incrementHitCount failed cacheKey={}", cacheKey, e);
        }
    }

    private void degrade(String cacheKey) {
        try {
            SemanticCache update = new SemanticCache();
            update.setStatus(CacheStatus.DEGRADED);
            semanticCacheMapper.update(update,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SemanticCache>()
                            .eq(SemanticCache::getCacheKey, cacheKey));
            redisTemplate.delete(REDIS_KEY_PREFIX + cacheKey);
            milvusService.deleteByCacheKey(cacheKey);
            log.info("[SemanticCache] degraded cacheKey={}", cacheKey);
            // B6: degrade 埋点. 放在 catch 外的"全部三件存储动作成功后", 避免 Redis 删失败
            // 但 MySQL 已置 DEGRADED 的中间态被多计 (但这场景不会发生, 因为整个 try 块走完才算 ok).
            graphMetricsCollector.recordCacheDegraded();
        } catch (Exception e) {
            log.warn("[SemanticCache] degrade failed cacheKey={}", cacheKey, e);
        }
    }

    private String safeToJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[SemanticCache] json serialize failed", e);
            return null;
        }
    }

    // ==================== Key 生成 + 归一化 ====================

    /**
     * 暴露给外部 (FinalizeNode / Controller 写 chat_message.cache_key 前先算一次).
     *
     * <p>cacheKey = MD5(featureName + "|" + intent.code + "|" + normalize(query)).
     * 加 Intent 维度避免不同意图 (how_to / troubleshoot / feature_intro) 下相同 query
     * 的回答风格差异被同一缓存覆盖.</p>
     */
    public String computeCacheKey(String featureName, Intent intent, String query) {
        String normalized = normalize(query);
        String intentCode = intent == null ? "default" : intent.getCode();
        String raw = (featureName == null ? "" : featureName)
                + "|" + intentCode
                + "|" + normalized;
        return md5(raw);
    }

    /**
     * 归一化规则 (L1 字面等价):
     * <ol>
     *   <li>trim</li>
     *   <li>全角转半角 (FF01-FF5E → 0021-007E, 全角空格 3000 → 半角空格)</li>
     *   <li>连续空白 (含 \t \n) 压成单个空格</li>
     *   <li>ASCII 字母小写化 (中文不动)</li>
     *   <li>去掉末尾标点 (?？!！。.)</li>
     * </ol>
     */
    static String normalize(String s) {
        if (s == null) return "";
        // 1. trim
        String t = s.trim();
        // 2. 全角转半角
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        t = sb.toString();
        // 3. 连续空白压一个空格
        t = t.replaceAll("\\s+", " ");
        // 4. ASCII 字母小写化 (只动 ASCII, 中文 toLowerCase 也没影响, 但显式只动 ASCII 更清晰)
        StringBuilder lower = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 'A' && c <= 'Z') lower.append((char) (c + 32));
            else lower.append(c);
        }
        t = lower.toString();
        // 5. 去掉末尾标点
        while (!t.isEmpty()) {
            char last = t.charAt(t.length() - 1);
            if (last == '?' || last == '!' || last == '.' || last == '。') {
                t = t.substring(0, t.length() - 1);
            } else {
                break;
            }
        }
        return t;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("MD5 failed", e);
        }
    }

    // ==================== 返回结构 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheLookupResult {
        private boolean hit;
        /** "L1" / "L2" / null. */
        private String hitLayer;
        private String cacheKey;
        private CacheEntry entry;
        /** L2 命中时填, L1 不填. */
        private Double similarity;

        public static CacheLookupResult miss() {
            return new CacheLookupResult(false, null, null, null, null);
        }
        public static CacheLookupResult hitL1(String key, CacheEntry e) {
            return new CacheLookupResult(true, "L1", key, e, null);
        }
        public static CacheLookupResult hitL2(String key, CacheEntry e, double sim) {
            return new CacheLookupResult(true, "L2", key, e, sim);
        }
    }

    /**
     * 按 feature 聚合统计 (B6 方案 D). 大屏"缓存详情"表格的行数据.
     */
    @Data
    public static class CacheFeatureStat {
        /** 功能名 (= semantic_cache.feature_name) */
        private String featureName;
        /** 该 feature 名下的缓存条目总数 (含 ACTIVE/DEGRADED/INVALID 所有状态) */
        private int totalEntries;
        /** 累计命中次数 = SUM(hit_count). 反映该 feature 被复用程度. */
        private long totalHits;
        /** 累计负反馈分 = SUM(feedback_score). 高值 = 答案质量差. */
        private long totalFeedbackScore;
        /** status=ACTIVE 的条目数 (可被命中) */
        private int activeCount;
        /** status=DEGRADED 的条目数 (反馈触阈被降级, 已清 Redis+Milvus 等待复活) */
        private int degradedCount;
        /** status=INVALID 的条目数 (重学/删除触发的失效, 等待 03:00 定时任务清理) */
        private int invalidCount;
    }
}