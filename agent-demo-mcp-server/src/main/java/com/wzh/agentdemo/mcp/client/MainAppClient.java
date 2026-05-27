package com.wzh.agentdemo.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /**
     * B5: 工单按钮场景 — 把"工单成功的事实"回填到主应用.
     *
     * <p><b>触发时机</b>: 仅当工单按钮场景下 (ticketButtonTriggeredBy != null),
     * TicketSystem 创建工单成功 (code=200, ticketNo 非空) 时调用.
     * 普通对话提工单 (ticketButtonTriggeredBy=null) 跳过此回调.</p>
     *
     * <p><b>main 端动作</b>:
     * <ol>
     *   <li>UPDATE chat_message SET submitted_ticket_id={ticketNo} WHERE id={targetAssistantMessageId}</li>
     *   <li>读 chat_message[targetId].cache_key, 非空时给该 cacheKey 累加工单权重负反馈分 (默认 +3)</li>
     * </ol>
     *
     * <p><b>同步阻塞设计</b>: 调用是 sync HTTP, 本地网 RTT < 50ms 可接受.
     * 收益是 LLM 答复"已提交工单 TK-xxx"时, DB 里的 submitted_ticket_id 状态已经一致 (不是占位 SUBMITTING).
     * 失败时 (callback 返回非 2xx 或网络异常) MCP 不抛, 让 main 端 handleDone 兜底回滚占位.</p>
     *
     * @param targetAssistantMessageId 被吐槽的那条 assistant 消息 id (从 McpMeta 取)
     * @param ticketNo 工单号 (TicketSystem 返回, 格式 TK-yyyyMMdd-NNNN)
     * @return main 端返回的 JSON 字符串 (成功时 {"success":true,...}, 失败 {"success":false,"message":"..."})
     */
    public String notifyTicketCallback(long targetAssistantMessageId, String ticketNo) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("targetAssistantMessageId", targetAssistantMessageId);
            body.put("ticketNo", ticketNo);
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/ticket/callback"))
                    .header("X-Internal-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[MainAppClient] ticket callback 成功 targetId={} ticketNo={} body={}",
                        targetAssistantMessageId, ticketNo, response.body());
                return response.body();
            }
            log.warn("[MainAppClient] ticket callback 状态码异常 status={} body={}",
                    response.statusCode(), response.body());
            return String.format("{\"success\":false,\"message\":\"主应用返回状态 %d\"}",
                    response.statusCode());
        } catch (Exception e) {
            log.error("[MainAppClient] ticket callback 调用失败 targetId={} ticketNo={}",
                    targetAssistantMessageId, ticketNo, e);
            return String.format("{\"success\":false,\"message\":\"callback 调用失败: %s\"}",
                    e.getMessage() == null ? "unknown" : e.getMessage().replace("\"", "'"));
        }
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