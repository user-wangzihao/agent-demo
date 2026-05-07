package com.wzh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生产侧 RAG 检索配置.
 *
 * <p>读取 application.yml 中 {@code rag.prod.*} 配置项.
 * 与评估侧的 {@link RewriteProperties}/{@link RerankProperties} 互不干扰,
 * 用于线上 AgentService 的检索流水线开关.</p>
 *
 * @author wzh
 * @since 2026-05-07
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.prod")
public class ProductionRetrieveProperties {

    /** feature_aware 总开关; false = 跳过 feature 流程, 直接走 fallback */
    private boolean featureAwareEnabled = true;

    /** 用户没传 feature 时是否调 LLM 提取 */
    private boolean featureExtractEnabled = true;

    /** feature 未命中时是否走 rewriting+rerank 兜底; false = 直接 baseline */
    private boolean rewritingFallbackEnabled = true;

    /** 兜底链路是否启用 reranker */
    private boolean rerankerEnabled = true;

    /** 最终返回给 postProcess 的条数 (与原 milvusService.search(_, 8) 保持一致) */
    private int finalTopK = 8;

    /** feature_aware 路径的 Milvus 检索 top-K */
    private int featureAwareTopK = 8;

    /** feature 提取 LLM 温度 (低温度 = 输出稳定) */
    private float temperature = 0.1f;

    /** feature 提取 LLM maxTokens (输出只有一个 feature_name, 100 足够) */
    private int maxTokens = 100;
}