package com.wzh.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 调用 TicketSystem REST 端点获取工单统计数据.
 *
 * <p><b>为什么不走 MCP</b>: MCP 是 LLM Agent 的工具调用通道，语义是"让 LLM 决定何时调用"。
 * 大屏数据集成是确定性的定时拉取，走 REST 直调更直接，不消耗 LLM token，
 * 也不依赖 MCP Server 的生命周期.</p>
 *
 * <p><b>HTTP 客户端</b>: 复用 JDK 内置 {@link HttpClient}，与 MCP Server 侧的
 * {@code MainAppClient} 保持一致的选型，无需引入额外依赖.</p>
 *
 * <p><b>容错</b>: 任何异常（网络超时、TicketSystem 宕机、401）均返回 0，
 * 由 {@link DashboardService} 的 safeLong 兜底，不影响大屏其他 KPI 卡.</p>
 */
@Slf4j
@Service
public class TicketStatsClient {

    private static final String TODAY_COUNT_PATH = "/api/stats/today-count";

    /** 连接 + 读取总超时，与 PrometheusQueryClient 保持同量级. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    public TicketStatsClient(
            @Value("${ticket-system.base-url}") String baseUrl,
            @Value("${ticket-system.api-key}") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取 TicketSystem 今日工单数.
     *
     * @return 今日工单数；网络异常或 TicketSystem 不可达时返回 0
     */
    public long getTodayCount() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + TODAY_COUNT_PATH))
                    .header("X-Api-Key", apiKey)
                    .GET()
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[TICKET-STATS-CLIENT] non-200 status={} body={}",
                        response.statusCode(), response.body());
                return 0L;
            }

            // 解析 {"code":200,"message":"success","data":3}
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                log.warn("[TICKET-STATS-CLIENT] data field missing, body={}", response.body());
                return 0L;
            }
            long count = dataNode.asLong(0L);
            log.debug("[TICKET-STATS-CLIENT] today-count={}", count);
            return count;

        } catch (Exception e) {
            log.warn("[TICKET-STATS-CLIENT] getTodayCount failed, fallback 0. err={}",
                    e.getMessage());
            return 0L;
        }
    }
}