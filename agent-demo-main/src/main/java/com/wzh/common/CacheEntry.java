package com.wzh.common;

import com.wzh.graph.support.SourceInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语义缓存 Redis value 结构 (第3刀).
 *
 * <p>Redis key = {@code cache:answer:{cacheKey}}, TTL 24h.</p>
 *
 * <p>命中时由 CacheCheckNode 反序列化后写入 Graph state, FinalizeNode 据此短路输出.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheEntry {

    /** 缓存唯一键 (=MySQL 主表 cache_key, 反查 MySQL 校验 status 用). */
    private String cacheKey;

    /** 所属功能名. */
    private String featureName;

    /** 缓存的 AI 回答全文. */
    private String answerText;

    /** 引用来源列表. SourceInfo 是顶层类 (第六刀 B3 产物), 直接序列化. */
    private List<SourceInfo> sources;

    /** 相关图片 URL 列表 (B3-a: 单独缓存, 避免命中时无法从 sources 反推). */
    private List<String> relatedImages;

    /** 写入时刻毫秒时间戳, 调试用. */
    private long createTimeMs;
}