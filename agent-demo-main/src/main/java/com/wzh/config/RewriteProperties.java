package com.wzh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Query Rewriting 配置.
 *
 * <p>用于阶段 2 的"查询改写 + 多路并行检索 + RRF 融合"流水线.
 * 参考 {@link RerankProperties} 的设计风格,失败降级策略保持一致.</p>
 *
 * <p><b>配置位置</b>: {@code application.yml} 下的 {@code rag.rewrite.*}.</p>
 *
 * @author wzh
 * @since 2026-05-06
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.rewrite")
public class RewriteProperties {

    /**
     * 是否启用 Query Rewriting.
     * <p>评估接口选了 rewriting/full pipeline + 此开关 = true,才会走改写流程.</p>
     */
    private boolean enabled = true;

    /**
     * 改写用的 LLM 模型.
     * <p>推荐 qwen-turbo (快、便宜);改写是简单任务,不需要 qwen-plus.</p>
     */
    private String model = "qwen-turbo";

    /**
     * 生成的改写数量 (不含原 query).
     * <p>总检索路数 = numRewrites + 1 (原 query 当兜底).</p>
     * <p>推荐 2;1 条会丢失多角度优势,3+ 条同质化严重且 cost 线性增长.</p>
     */
    private int numRewrites = 2;

    /**
     * LLM 调用温度.
     * <p>改写是确定性场景,推荐 0.2-0.3;过低会僵化,过高会跑偏.</p>
     */
    private float temperature = 0.3f;

    /**
     * LLM 输出最大 token 数.
     * <p>2 条改写每条 ≤ 30 字,JSON 包装后 ~150 token 足够;留 200 防止截断.</p>
     */
    private int maxTokens = 200;

    /**
     * LLM 调用超时 (毫秒).
     * <p>qwen-turbo 一般 < 2s,留 5s 防尾延迟.</p>
     */
    private int timeoutMs = 5000;

    /**
     * 单路检索的 top-K (RRF 合并前每路召回多少条).
     * <p>3 路 × 15 = 最多 45 条候选,去重后通常 25-35 条 — 给 reranker 留足空间.</p>
     */
    private int recallTopK = 15;

    /**
     * RRF 融合的 k 参数 (smoothing factor).
     * <p>公式: score = Σ 1 / (k + rank);k 越大 → 排名差异被弱化;
     * 60 是 Cormack et al. 2009 论文推荐值,业界标准.</p>
     */
    private int rrfK = 60;

    /**
     * 改写失败时是否降级.
     * <p>true: 改写失败 → 退化为单 query baseline 流程 (推荐);
     * false: 直接返回空结果.</p>
     */
    private boolean fallbackOnError = true;

    /**
     * 并行检索线程池大小.
     * <p>= numRewrites + 1 即可,留点 buffer 设 4 — 同时支持 num-rewrites 设到 3.</p>
     */
    private int threadPoolSize = 4;
}