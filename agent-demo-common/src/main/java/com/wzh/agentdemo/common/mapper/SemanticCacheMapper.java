package com.wzh.agentdemo.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzh.agentdemo.common.entity.SemanticCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 语义缓存 Mapper.
 *
 * <p>大部分操作走 MyBatis-Plus 的 BaseMapper + LambdaQueryWrapper,
 * 仅对原子性敏感的 +N 操作走自定义 SQL 防止并发覆盖.</p>
 */
@Mapper
public interface SemanticCacheMapper extends BaseMapper<SemanticCache> {

    /**
     * 命中时原子累加 hit_count + 更新 last_hit_time.
     *
     * <p>用 SQL 层 {@code hit_count = hit_count + 1} 避免"查-改-写"的并发覆盖.</p>
     *
     * @return 影响行数 (0 = 记录不存在)
     */
    int incrementHitCount(@Param("cacheKey") String cacheKey,
                          @Param("now") LocalDateTime now);

    /**
     * 负反馈累加. 原子 +delta 后返回新值, 调用方根据新值是否 ≥ threshold 决定是否置 DEGRADED.
     *
     * <p>用 SQL 层 {@code feedback_score = feedback_score + #{delta}} 防并发覆盖.
     * 返回新值通过紧随其后的 SELECT 拿, 不依赖单次 UPDATE...RETURNING (MySQL 不支持).</p>
     *
     * @return 影响行数 (0 = 记录不存在)
     */
    int incrementFeedbackScore(@Param("cacheKey") String cacheKey,
                               @Param("delta") int delta);

    /**
     * 按 featureName 批量置 INVALID. 管理员重学文档 / 操作 FAQ 时调用.
     *
     * @return 失效的记录数
     */
    int invalidateByFeatureName(@Param("featureName") String featureName);
}