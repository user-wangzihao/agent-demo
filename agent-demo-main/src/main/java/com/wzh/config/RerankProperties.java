package com.wzh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Reranker 配置.
 *
 * <p>读取 application.yml 中 {@code rag.rerank.*} 配置项.</p>
 *
 * @author wzh
 * @since 2026-05-05
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.rerank")
public class RerankProperties {

    /** 是否启用 (评估接口选 reranker pipeline 时,这个开关也要为 true 才生效) */
    private boolean enabled = true;

    /** 模型: gte-rerank-v2 / qwen3-rerank */
    private String model = "gte-rerank-v2";

    /** DashScope rerank API 端点 */
    private String endpoint = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** 粗召回 top-K (送给 reranker 的候选数) */
    private int recallTopK = 20;

    /** 精排后保留 top-N */
    private int rerankTopN = 5;

    /** 单条文档截断长度 (字符数) */
    private int maxDocChars = 2500;

    /** HTTP 连接超时 (ms) */
    private int connectTimeoutMs = 3000;

    /** HTTP 读取超时 (ms) */
    private int readTimeoutMs = 8000;

    /** 失败时是否降级到 baseline */
    private boolean fallbackOnError = true;
}