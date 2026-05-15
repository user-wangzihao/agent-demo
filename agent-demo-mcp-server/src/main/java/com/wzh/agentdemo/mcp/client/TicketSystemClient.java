package com.wzh.agentdemo.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用外部工单系统（TicketSystem）。
 * TicketSystem 实际接口路径：
 *   POST /api/ticket/create            创建工单
 *   GET  /api/ticket/status/{ticketNo} 查询工单
 * 鉴权头：X-Api-Key
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketSystemClient {

    private final ObjectMapper objectMapper;

    @Value("${ticket-system.base-url}")
    private String baseUrl;

    @Value("${ticket-system.api-key}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 提交工单。
     * 注意请求体字段必须与 TicketSystem 的 CreateTicketRequest 一致:
     *   source / userId / userName / title / description / agentSessionId / priority
     *   / relatedFeatureId / relatedFeatureName  (第五刀新增)
     */
    public String submitTicket(String title, String description, String priority,
                               String userId, String userName, Long sessionId,
                               String featureName, String chatHistoryJson) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "AgentDemo");
            body.put("userId", userId);
            body.put("userName", userName);
            body.put("title", title);
            body.put("description", description);
            body.put("agentSessionId", sessionId == null ? null : String.valueOf(sessionId));
            body.put("priority", priority == null ? "NORMAL" : priority);
            // 第五刀新增:feature_name 透传(featureId 暂时为空,TicketSystem 侧默认 NULL)
            body.put("relatedFeatureId", null);
            body.put("relatedFeatureName",
                    (featureName == null || featureName.isBlank()) ? "通用FAQ" : featureName);
            body.put("chatHistory", chatHistoryJson == null ? "[]" : chatHistoryJson);

            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ticket/create"))
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("工单创建成功: {}", response.body());
                return response.body();
            }
            log.warn("工单系统响应异常 status={}, body={}", response.statusCode(), response.body());
            return String.format(
                    "{\"success\":false,\"message\":\"工单系统返回 %d: %s\"}",
                    response.statusCode(),
                    response.body().replace("\"", "'"));
        } catch (Exception e) {
            log.error("提交工单失败", e);
            return String.format("{\"success\":false,\"message\":\"%s\"}",
                    e.getMessage().replace("\"", "'"));
        }
    }

    /**
     * 查询工单状态。
     */
    public String queryTicketStatus(String ticketNo) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ticket/status/" + ticketNo))
                    .header("X-Api-Key", apiKey)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return String.format(
                        "{\"success\":false,\"message\":\"未找到工单号 %s\"}", ticketNo);
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            return String.format(
                    "{\"success\":false,\"message\":\"工单系统返回 %d\"}",
                    response.statusCode());
        } catch (Exception e) {
            log.error("查询工单失败 ticketNo={}", ticketNo, e);
            return String.format("{\"success\":false,\"message\":\"%s\"}",
                    e.getMessage().replace("\"", "'"));
        }
    }
}