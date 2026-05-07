package com.wzh.agentdemo.evaltools.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzh.agentdemo.evaltools.config.AuditConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DashScope HTTP 客户端。
 * <p>独立实现，不依赖任何 Spring AI / Spring AI Alibaba。</p>
 *
 * <ul>
 *   <li>embedding: 兼容 DashScope 原生协议</li>
 *   <li>chat: 走 OpenAI 兼容模式 (/compatible-mode/v1/chat/completions)</li>
 * </ul>
 */
@Slf4j
public class DashScopeClient {

    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashScopeClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(AuditConfig.HTTP_TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(AuditConfig.HTTP_TIMEOUT_SECONDS))
                .writeTimeout(Duration.ofSeconds(AuditConfig.HTTP_TIMEOUT_SECONDS))
                .build();
    }

    // ==================== Embedding ====================
    public List<Float> embed(String text) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", AuditConfig.EMBEDDING_MODEL);
        ObjectNode input = body.putObject("input");
        ArrayNode texts = input.putArray("texts");
        texts.add(text);

        Request req = new Request.Builder()
                .url(AuditConfig.EMBEDDING_ENDPOINT)
                .header("Authorization", "Bearer " + AuditConfig.DASHSCOPE_API_KEY)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json")))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Embedding API failed: " + resp.code() + " " + resp.message());
            }
            String respBody = resp.body() == null ? "" : resp.body().string();
            JsonNode root = mapper.readTree(respBody);
            JsonNode embeddings = root.path("output").path("embeddings");
            if (!embeddings.isArray() || embeddings.size() == 0) {
                throw new IOException("Embedding API returned no embeddings: " + respBody);
            }
            JsonNode vec = embeddings.get(0).path("embedding");
            List<Float> result = new ArrayList<>(vec.size());
            for (JsonNode v : vec) result.add(v.floatValue());
            return result;
        }
    }

    // ==================== Chat (OpenAI 兼容模式) ====================
    public String chat(String model, String userPrompt, double temperature) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        ArrayNode msgs = body.putArray("messages");
        ObjectNode userMsg = msgs.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        Request req = new Request.Builder()
                .url(AuditConfig.CHAT_ENDPOINT)
                .header("Authorization", "Bearer " + AuditConfig.DASHSCOPE_API_KEY)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json")))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String respBody = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("Chat API failed: " + resp.code() + " " + resp.message() + " body=" + respBody);
            }
            JsonNode root = mapper.readTree(respBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode()) {
                throw new IOException("Chat API returned no content: " + respBody);
            }
            return content.asText();
        }
    }

    /** 带重试的 chat */
    public String chatWithRetry(String model, String userPrompt, double temperature) {
        IOException last = null;
        for (int attempt = 0; attempt <= AuditConfig.LLM_MAX_RETRY; attempt++) {
            try {
                return chat(model, userPrompt, temperature);
            } catch (IOException e) {
                last = e;
                log.warn("chat 调用失败 (attempt {}/{}): {}", attempt + 1, AuditConfig.LLM_MAX_RETRY + 1, e.getMessage());
                try {
                    Thread.sleep(AuditConfig.LLM_RETRY_BACKOFF_MS * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("chat 调用最终失败 model={}: {}", model, last == null ? "unknown" : last.getMessage());
        return null;
    }
}
