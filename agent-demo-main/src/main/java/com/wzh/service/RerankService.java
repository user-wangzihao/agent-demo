package com.wzh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzh.config.DashScopeConfig;
import com.wzh.config.RerankProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DashScope 文本重排 (Reranker) 服务.
 *
 * <p>封装 gte-rerank-v2 的 HTTP 调用,把"粗召回的候选文档"按相关度重新排序.
 * 目前只在评估流水线里使用 (RagEvalAgentService.PIPELINE_RERANKER),
 * 暂不接入线上 AgentService.</p>
 *
 * <p><b>API 文档</b>:
 * <a href="https://help.aliyun.com/zh/model-studio/text-rerank-api">通用文本排序模型</a></p>
 *
 * <p><b>设计要点</b>:
 * <ul>
 *   <li>请求体设 {@code return_documents: false} — 我们只要 index + score,省 token/带宽</li>
 *   <li>失败降级 — 出错时返回 null,由调用方决定是否走 baseline</li>
 *   <li>文档截断 — 单条超 {@code maxDocChars} 截断,避免触达 4000 token 上限</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {

    private final RerankProperties rerankProperties;
    private final DashScopeConfig dashScopeConfig;
    private final ObjectMapper objectMapper;

    /** 独立的 RestTemplate,带超时;不污染全局 */
    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        // 用 SimpleClientHttpRequestFactory 直接设超时,跨 Spring Boot 版本兼容
        // (RestTemplateBuilder 在 Spring Boot 3.4+ 改了 API,这里规避版本差异)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(rerankProperties.getConnectTimeoutMs());
        factory.setReadTimeout(rerankProperties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
        log.info("[RERANK] 初始化完成 model={} endpoint={} timeout={}ms/{}ms",
                rerankProperties.getModel(),
                rerankProperties.getEndpoint(),
                rerankProperties.getConnectTimeoutMs(),
                rerankProperties.getReadTimeoutMs());
    }

    // =========================================================================
    // 数据类
    // =========================================================================

    /**
     * 输入候选: 一条粗召回的 chunk
     */
    public static class RerankCandidate {
        /** chunk 唯一 ID — reranker 不感知这个字段,但调用方需要用它把结果映射回原始数据 */
        public final String chunkId;
        /** 文档文本 — 实际送给 reranker 的内容 */
        public final String content;

        public RerankCandidate(String chunkId, String content) {
            this.chunkId = chunkId;
            this.content = content;
        }
    }

    /**
     * 输出结果: 一条重排后的命中
     */
    public static class RerankHit {
        /** 在输入候选数组里的原下标 */
        public final int originalIndex;
        /** 对应的 chunk_id (从原候选透传) */
        public final String chunkId;
        /** 相关度分数,越大越相关 */
        public final double score;

        public RerankHit(int originalIndex, String chunkId, double score) {
            this.originalIndex = originalIndex;
            this.chunkId = chunkId;
            this.score = score;
        }
    }

    // =========================================================================
    // 主入口
    // =========================================================================

    /**
     * 调 DashScope 对候选文档重排.
     *
     * @param query      用户原始 query
     * @param candidates 粗召回的候选文档 (建议 ≤ 20)
     * @param topN       精排后保留多少条;传 ≤0 则用配置默认
     * @return 重排结果列表 (按 score 降序);失败返回 null,由调用方决定降级策略
     */
    public List<RerankHit> rerank(String query, List<RerankCandidate> candidates, int topN) {
        if (query == null || query.isEmpty() || candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        if (topN <= 0) {
            topN = rerankProperties.getRerankTopN();
        }
        // 防御性: top_n 不能超过 documents 数量
        topN = Math.min(topN, candidates.size());

        long start = System.currentTimeMillis();
        try {
            // 1. 构造请求体
            String requestBody = buildRequestBody(query, candidates, topN);

            // 2. 发起 HTTP 请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(dashScopeConfig.getApiKey());

            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
            String response = restTemplate.postForObject(
                    rerankProperties.getEndpoint(), request, String.class);

            // 3. 解析结果
            List<RerankHit> hits = parseResponse(response, candidates);
            long latency = System.currentTimeMillis() - start;
            log.info("[RERANK] 重排完成 query={} candidates={} returned={} latency={}ms",
                    truncate(query, 50), candidates.size(), hits.size(), latency);
            return hits;

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[RERANK] 重排失败 query={} candidates={} latency={}ms err={}",
                    truncate(query, 50), candidates.size(), latency, e.getMessage());
            return null;  // 返回 null 表示"重排失败",调用方按 fallback-on-error 决策
        }
    }

    // =========================================================================
    // 请求 / 响应处理
    // =========================================================================

    /**
     * 构造 DashScope rerank 请求体:
     * <pre>
     * {
     *   "model": "gte-rerank-v2",
     *   "input": {
     *     "query": "...",
     *     "documents": ["...", "...", ...]
     *   },
     *   "parameters": {
     *     "return_documents": false,
     *     "top_n": 5
     *   }
     * }
     * </pre>
     */
    private String buildRequestBody(String query, List<RerankCandidate> candidates, int topN)
            throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", rerankProperties.getModel());

        ObjectNode input = root.putObject("input");
        input.put("query", query);
        ArrayNode docs = input.putArray("documents");
        for (RerankCandidate c : candidates) {
            docs.add(truncate(c.content == null ? "" : c.content,
                    rerankProperties.getMaxDocChars()));
        }

        ObjectNode params = root.putObject("parameters");
        params.put("return_documents", false);  // 节省带宽,我们只要 index + score
        params.put("top_n", topN);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析 DashScope 响应:
     * <pre>
     * {
     *   "output": {
     *     "results": [
     *       { "index": 2, "relevance_score": 0.93 },
     *       { "index": 0, "relevance_score": 0.71 }
     *     ]
     *   },
     *   "usage": { "total_tokens": 79 },
     *   "request_id": "..."
     * }
     * </pre>
     */
    private List<RerankHit> parseResponse(String response, List<RerankCandidate> candidates)
            throws Exception {
        if (response == null || response.isEmpty()) {
            throw new RuntimeException("rerank API 返回空响应");
        }
        JsonNode root = objectMapper.readTree(response);
        // 检查错误码 (DashScope 失败时会返回 code/message,成功时这两个字段为空字符串)
        JsonNode codeNode = root.get("code");
        if (codeNode != null && !codeNode.isNull()
                && !codeNode.asText().isEmpty()) {
            throw new RuntimeException("rerank API 返回错误 code=" + codeNode.asText()
                    + " message=" + (root.has("message") ? root.get("message").asText() : ""));
        }

        JsonNode output = root.get("output");
        if (output == null || !output.has("results")) {
            throw new RuntimeException("rerank 响应缺少 output.results 字段: " + truncate(response, 200));
        }

        List<RerankHit> hits = new ArrayList<>();
        for (JsonNode r : output.get("results")) {
            int idx = r.get("index").asInt();
            double score = r.get("relevance_score").asDouble();
            if (idx < 0 || idx >= candidates.size()) {
                log.warn("[RERANK] 返回的 index={} 越界 (size={}), 跳过", idx, candidates.size());
                continue;
            }
            hits.add(new RerankHit(idx, candidates.get(idx).chunkId, score));
        }
        // DashScope 一般会按 score 降序返回,这里再保险排一次
        hits.sort(Comparator.comparingDouble((RerankHit h) -> h.score).reversed());
        return hits;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // =========================================================================
    // 暴露 properties 给上层 (避免上层再注入 RerankProperties)
    // =========================================================================
    public RerankProperties getProperties() {
        return rerankProperties;
    }
}