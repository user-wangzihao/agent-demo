package com.wzh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prometheus Query API 客户端 (B4 引入).
 *
 * <p><b>定位</b>: 让后端能像 Grafana 一样查 Prometheus 时序数据. 大屏 KPI 卡 #2
 * (Graph 耗时) 和卡 #4 (今日 Token) 的数据来自 Prometheus, 本类负责把
 * PromQL 表达式发出去、把 JSON 响应解析成可用的数值/字符串.</p>
 *
 * <p><b>设计原则</b>:
 * <ol>
 *   <li><b>独立 RestTemplate + 显式超时</b>: Prometheus 偶尔慢, 不能拖大屏整体响应; 1.5s 强制熔断</li>
 *   <li><b>失败降级</b>: 任何 HTTP 错误 / JSON 解析失败都返回 null, 调用方按 KPI 各自定义默认值;
 *       绝不向上抛, 大屏不能因为 Prometheus 挂了就整页 500</li>
 *   <li><b>不做缓存</b>: Prometheus 端本身就是高速 in-memory, 加缓存只会让数据滞后</li>
 *   <li><b>不暴露原始 JSON</b>: 解析逻辑封装在本类, 调用方只见 Double/String/Map</li>
 * </ol></p>
 *
 * <p><b>Prometheus Query API 返回结构</b> (instant query):
 * <pre>
 * {
 *   "status": "success",
 *   "data": {
 *     "resultType": "vector",
 *     "result": [
 *       { "metric": {"scene": "chat_main"}, "value": [1716372000, "12345.6"] },
 *       ...
 *     ]
 *   }
 * }
 * </pre>
 * 其中 value[0] 是 unix timestamp, value[1] 是字符串化的数值.</p>
 *
 * @author wzh
 * @since 2026-05-22 (B4)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrometheusQueryClient {

    /** Prometheus base URL, 默认 localhost:9090 (B4 本地部署形态). */
    @Value("${prometheus.base-url:http://localhost:9090}")
    private String baseUrl;

    /** 连接超时 ms. 1s 足够同机访问. */
    @Value("${prometheus.connect-timeout-ms:1000}")
    private int connectTimeoutMs;

    /** 读取超时 ms. 1.5s 是 Grafana 默认值, 平衡用户体验和兜底. */
    @Value("${prometheus.read-timeout-ms:1500}")
    private int readTimeoutMs;

    private final ObjectMapper objectMapper;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
        log.info("[PROM-QUERY] initialized base={} timeout={}ms/{}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 公共 query 方法 ====================

    /**
     * 执行 instant query, 返回单个标量值 (取 result[0].value[1]).
     *
     * <p>适用场景: PromQL 表达式聚合后只剩一个时序 (例如 {@code sum(...)}),
     * 调用方期望拿一个 Double.</p>
     *
     * @param promql PromQL 表达式
     * @return Double 数值; 任何失败 (HTTP / JSON / 空结果) 都返回 null, 由调用方决定默认值
     */
    public Double queryScalar(String promql) {
        JsonNode root = queryRaw(promql);
        if (root == null) return null;
        try {
            JsonNode result = root.path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                log.debug("[PROM-QUERY] scalar query empty result. promql={}", promql);
                return null;
            }
            JsonNode value = result.get(0).path("value");
            if (!value.isArray() || value.size() < 2) {
                return null;
            }
            return value.get(1).asDouble();
        } catch (Exception e) {
            log.warn("[PROM-QUERY] scalar parse failed promql={}", promql, e);
            return null;
        }
    }

    /**
     * 执行 instant query, 返回 label_value → 数值 的映射.
     *
     * <p>适用场景: PromQL 表达式按某个 label 聚合 (例如
     * {@code sum by (scene) (...)}), 调用方期望拿"每个 label 值对应一个数"的
     * 字典. 字典 key 是指定的 label 在该结果时序里的值.</p>
     *
     * @param promql      PromQL 表达式
     * @param groupingLabel 要作为 map key 的 label 名 (必须是 PromQL 中 {@code by (...)} 里的 label)
     * @return LinkedHashMap 保留 Prometheus 返回顺序 (一般是字典序);
     *         任何失败返回空 map, 不返回 null (调用方少一次判空)
     */
    public Map<String, Double> queryGrouped(String promql, String groupingLabel) {
        Map<String, Double> map = new LinkedHashMap<>();
        JsonNode root = queryRaw(promql);
        if (root == null) return map;
        try {
            JsonNode result = root.path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return map;
            }
            for (Iterator<JsonNode> it = result.elements(); it.hasNext(); ) {
                JsonNode item = it.next();
                String labelValue = item.path("metric").path(groupingLabel).asText("unknown");
                JsonNode value = item.path("value");
                if (value.isArray() && value.size() >= 2) {
                    map.put(labelValue, value.get(1).asDouble());
                }
            }
        } catch (Exception e) {
            log.warn("[PROM-QUERY] grouped parse failed promql={} label={}",
                    promql, groupingLabel, e);
        }
        return map;
    }

    // ==================== 底层 HTTP + JSON 解析 ====================

    /**
     * 调 Prometheus /api/v1/query 端点, 返回根 JsonNode.
     *
     * <p>任何 HTTP / IO / JSON 异常都被吞掉返回 null, 由调用方降级.</p>
     */
    private JsonNode queryRaw(String promql) {
        if (promql == null || promql.isBlank()) return null;
        long start = System.currentTimeMillis();
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/v1/query")
                    .queryParam("query", promql)
                    .build()
                    .encode()
                    .toUriString();
            String body = restTemplate.getForObject(url, String.class);
            long latency = System.currentTimeMillis() - start;
            log.debug("[PROM-QUERY] {}ms promql={}", latency, promql);

            if (body == null || body.isEmpty()) return null;
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText("");
            if (!"success".equals(status)) {
                log.warn("[PROM-QUERY] non-success status={} promql={}", status, promql);
                return null;
            }
            return root;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            // warn 级别 — 单次失败不要刷屏 error
            log.warn("[PROM-QUERY] failed {}ms promql={} err={}",
                    latency, promql, e.getMessage());
            return null;
        }
    }
}
