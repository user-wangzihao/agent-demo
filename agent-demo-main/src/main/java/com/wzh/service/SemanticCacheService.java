package com.wzh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.CacheStatus;
import com.wzh.agentdemo.common.entity.SemanticCache;
import com.wzh.agentdemo.common.mapper.SemanticCacheMapper;
import com.wzh.common.CacheEntry;
import com.wzh.config.SemanticCacheProperties;
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
import java.util.HexFormat;
import java.util.List;

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
    public CacheLookupResult lookup(String query, String featureName) {
        if (!properties.isEnabled() || query == null || query.isBlank() || featureName == null) {
            return CacheLookupResult.miss();
        }

        // L1 精确命中
        String l1Key = computeCacheKey(featureName, query);
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
     * @return 写入的 cacheKey (即使部分失败也返回 key, 调用方据此写 chat_message.cache_key)
     */
    public String put(String query, String featureName, String answerText, List<SourceInfo> sources) {
        if (!properties.isEnabled()) return null;
        if (query == null || query.isBlank() || featureName == null || answerText == null) return null;

        String cacheKey = computeCacheKey(featureName, query);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plusHours(properties.getTtlHours());

        // Step 1: MySQL
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
        try {
            semanticCacheMapper.insert(row);
        } catch (Exception e) {
            log.warn("[SemanticCache] MySQL insert failed cacheKey={}, skip Redis/Milvus", cacheKey, e);
            return null;
        }

        // Step 2: Redis
        CacheEntry entry = new CacheEntry(cacheKey, featureName, answerText, sources,
                System.currentTimeMillis());
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + cacheKey, json,
                    Duration.ofHours(properties.getTtlHours()));
        } catch (Exception e) {
            log.warn("[SemanticCache] Redis set failed cacheKey={}, continue to Milvus", cacheKey, e);
        }

        // Step 3: Milvus
        try {
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

    /** 暴露给外部 (FinalizeNode 写 chat_message.cache_key 前先算一次). */
    public String computeCacheKey(String featureName, String query) {
        String normalized = normalize(query);
        String raw = (featureName == null ? "" : featureName) + "|" + normalized;
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
}