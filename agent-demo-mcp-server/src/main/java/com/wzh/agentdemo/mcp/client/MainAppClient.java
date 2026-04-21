package com.wzh.agentdemo.mcp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 调用主应用（agent-demo-main）暴露的内部 HTTP 接口。
 * 用于触发需要主应用业务能力的工具（比如触发文档/视频学习）。
 */
@Slf4j
@Component
public class MainAppClient {

    @Value("${main-app.base-url}")
    private String baseUrl;

    @Value("${main-app.internal-api-key}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 触发文档学习（异步，主应用立即返回）
     */
    public String triggerDocumentLearning(long docId) {
        return post(String.format("/internal/learning/document/%d", docId));
    }

    /**
     * 触发视频学习（异步，主应用立即返回）
     */
    public String triggerVideoLearning(long videoId) {
        return post(String.format("/internal/learning/video/%d", videoId));
    }

    private String post(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("X-Internal-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            log.warn("主应用接口调用异常 path={}, status={}, body={}",
                    path, response.statusCode(), response.body());
            return String.format(
                    "{\"success\":false,\"message\":\"主应用返回状态 %d\"}",
                    response.statusCode());
        } catch (Exception e) {
            log.error("调用主应用失败 path={}", path, e);
            return String.format(
                    "{\"success\":false,\"message\":\"调用失败: %s\"}",
                    e.getMessage().replace("\"", "'"));
        }
    }
}