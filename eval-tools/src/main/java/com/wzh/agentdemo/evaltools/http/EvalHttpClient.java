package com.wzh.agentdemo.evaltools.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.evaltools.config.AuditConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 评估器统一 HTTP 客户端 (评估 CI Batch 3 引入).
 *
 * <p>封装对主应用 {@code /internal/eval/**} 和 {@code /api/auth/login} / {@code /api/graph/chat-stream}
 * 端点的调用, 统一处理:
 * <ul>
 *   <li>JSON 序列化/反序列化 (Jackson)</li>
 *   <li>X-Internal-Api-Key 鉴权头注入 (内部端点)</li>
 *   <li>Authorization: Bearer token (业务端点, Batch 6)</li>
 *   <li>HTTP 超时控制 (避免 LLM 兜底慢时单点拖死整个评估流程)</li>
 *   <li>非 200 响应的异常封装</li>
 *   <li>SSE 流式响应的事件解析与时序回调 (Batch 6)</li>
 * </ul>
 *
 * <p><b>Batch 6 扩展</b>: 新增 {@link #login(String, String)} 拿 token,
 * 以及 {@link #streamSse} 真打 SSE 端点测量端到端延迟. 已有 {@link #postJson} 零改动.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 3)
 */
@Slf4j
public class EvalHttpClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;

    public EvalHttpClient() {
        this(AuditConfig.MAIN_APP_BASE_URL, AuditConfig.INTERNAL_API_KEY);
    }

    public EvalHttpClient(String baseUrl, String apiKey) {
        this.baseUrl = stripTrailSlash(baseUrl);
        this.apiKey = apiKey;
        this.mapper = new ObjectMapper();
        // 超时设置: connect 短, read 较长 (LLM 兜底最坏可能 3s+, 但主应用侧 yml 已配 3000ms)
        // 这里给 10s 余量, 防止偶发抖动导致评估 case 失败.
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * POST JSON 到 {@code baseUrl + path}, 返回反序列化后的 Map.
     *
     * @param path     相对路径, 必须以 / 开头, 如 /internal/eval/intent
     * @param payload  请求体 (会被 Jackson 序列化为 JSON)
     * @return 响应体 (反序列化为 Map). 永不为 null; 失败抛 IOException.
     * @throws IOException 网络异常 / 非 200 状态码 / JSON 解析失败
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> postJson(String path, Map<String, Object> payload) throws IOException {
        String url = baseUrl + path;
        String bodyStr = mapper.writeValueAsString(payload == null ? Map.of() : payload);
        // 用 (MediaType, String) 旧顺序签名: okhttp 3.x 原生支持; 4.x 兼容保留 (@Deprecated 但可用).
        // 反过来用 (String, MediaType) 仅 4.x 支持. 选老顺序更稳定.
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, bodyStr))
                .header("X-Internal-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String respBody = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException(String.format(
                        "HTTP %d %s for %s, body: %s",
                        resp.code(), resp.message(), url,
                        respBody.length() > 200 ? respBody.substring(0, 200) + "..." : respBody));
            }
            if (respBody.isEmpty()) {
                throw new IOException("空响应体 for " + url);
            }
            return mapper.readValue(respBody, Map.class);
        }
    }

    // ==================== Batch 6 扩展: SSE 端到端延迟评估 ====================

    /**
     * 登录主应用获取 token (Batch 6).
     *
     * <p>调用 {@code POST /api/auth/login}, 返回登录响应里的 token 字段.
     * 该端点在 {@code AuthInterceptor} 中被排除拦截 (excludePathPatterns 含 /api/auth/**),
     * 无需任何鉴权头.</p>
     *
     * <p><b>评估期使用场景</b>: 评估器启动时调用一次拿到 token, 全程缓存复用.
     * token 默认有效期由主应用 yml 配置 (通常 24h), 远大于评估单次执行的几分钟,
     * 不需要刷新逻辑.</p>
     *
     * @param username 测试账号用户名
     * @param password 测试账号明文密码 (主应用当前是明文比对, 见 AuthController.login)
     * @return JWT token, 用于 Authorization: Bearer 头
     * @throws IOException 网络失败 / 凭据错误 / 响应格式异常
     */
    @SuppressWarnings("unchecked")
    public String login(String username, String password) throws IOException {
        String url = baseUrl + "/api/auth/login";
        String bodyStr = mapper.writeValueAsString(Map.of(
                "username", username,
                "password", password));
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, bodyStr))
                .header("Content-Type", "application/json")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String respBody = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException(String.format(
                        "登录 HTTP %d %s for %s, body: %s",
                        resp.code(), resp.message(), url, truncate(respBody, 200)));
            }
            Map<String, Object> wrapper = mapper.readValue(respBody, Map.class);
            // 主应用统一 Result 包装: { code, message, data: { token, userId, ... } }
            Object codeObj = wrapper.get("code");
            if (codeObj != null && !"200".equals(String.valueOf(codeObj))) {
                throw new IOException("登录失败: " + wrapper.get("message"));
            }
            Object dataObj = wrapper.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) {
                throw new IOException("登录响应无 data 字段: " + truncate(respBody, 200));
            }
            Object token = ((Map<String, Object>) data).get("token");
            if (token == null || String.valueOf(token).isBlank()) {
                throw new IOException("登录响应无 token 字段: " + truncate(respBody, 200));
            }
            return String.valueOf(token);
        }
    }

    /**
     * 打 SSE 流式端点, 按事件类型回调 (Batch 6).
     *
     * <p><b>主应用 SSE 协议</b>: 4 类 event name — {@code meta} / {@code token} / {@code done} / {@code error}.
     * 事件顺序: meta → token (多次) → done. error 在异常时替代 done.
     * 见 {@code MainGraphSseController#chatStream}.</p>
     *
     * <p><b>SSE 解析约定</b> (RFC: text/event-stream):
     * <ul>
     *   <li>空行 = event 分隔符</li>
     *   <li>{@code event: <name>} = 当前 event 类型</li>
     *   <li>{@code data: <payload>} = 当前 event 数据 (可多行, 自动拼接)</li>
     *   <li>{@code :} 开头 = 注释行, 忽略</li>
     * </ul>
     *
     * <p><b>callback 签名</b>: {@code (eventName, dataPayload) -> ...}.
     * 调用方关心的是事件**到达时刻**, 内部 data 内容由调用方自行解析 (本方法不做 JSON 解析).</p>
     *
     * <p><b>终止条件</b>: 收到 {@code done} 或 {@code error} 事件, 或 stream 自然结束 (服务器关闭连接),
     * 或读超时 ({@link #sseReadTimeoutSec} 秒). 终止后关闭 ResponseBody.</p>
     *
     * @param path     相对路径, 必须以 / 开头
     * @param payload  请求体 (Jackson 序列化)
     * @param token    JWT token, 用于 {@code Authorization: Bearer xxx}
     * @param callback 事件回调, 每次完整 event 触发一次
     * @throws IOException 网络失败 / 鉴权失败 / 读超时
     */
    public void streamSse(String path,
                          Map<String, Object> payload,
                          String token,
                          BiConsumer<String, String> callback) throws IOException {
        String url = baseUrl + path;
        String bodyStr = mapper.writeValueAsString(payload == null ? Map.of() : payload);

        // SSE 用专门的 client 实例: read 超时给更长 (端到端延迟最坏可能 60s+, LLM 慢时尤甚).
        // 不复用主 http 字段, 避免污染其他 RPC 调用.
        OkHttpClient sseClient = http.newBuilder()
                .readTimeout(sseReadTimeoutSec, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, bodyStr))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .build();

        try (Response resp = sseClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = resp.body() == null ? "" : resp.body().string();
                throw new IOException(String.format(
                        "SSE HTTP %d %s for %s, body: %s",
                        resp.code(), resp.message(), url, truncate(errBody, 200)));
            }
            ResponseBody respBody = resp.body();
            if (respBody == null) {
                throw new IOException("SSE 响应体为空 for " + url);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(respBody.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                String currentEvent = null;
                StringBuilder currentData = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        // 空行 = event 分隔符, 触发回调
                        if (currentEvent != null) {
                            callback.accept(currentEvent, currentData.toString());
                            if ("done".equals(currentEvent) || "error".equals(currentEvent)) {
                                return;  // 终止条件
                            }
                        }
                        currentEvent = null;
                        currentData.setLength(0);
                    } else if (line.startsWith(":")) {
                        // 注释行, 忽略
                    } else if (line.startsWith("event:")) {
                        currentEvent = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        // data 可能多行, RFC 规定多行用 \n 拼接 (data: 前缀去掉前导单个空格)
                        if (currentData.length() > 0) currentData.append("\n");
                        String dataPart = line.substring("data:".length());
                        if (dataPart.startsWith(" ")) dataPart = dataPart.substring(1);
                        currentData.append(dataPart);
                    }
                    // 其他行 (id:, retry: 等) SSE 规范定义但本系统不发, 忽略
                }
                // stream 自然结束但没收到 done — 兜底触发最后一次回调 (若有未完成 event)
                if (currentEvent != null && currentData.length() > 0) {
                    callback.accept(currentEvent, currentData.toString());
                }
            }
        }
    }

    /** SSE read timeout (秒). Batch 6 默认 60s, 容忍最坏 LLM 兜底慢. */
    private int sseReadTimeoutSec = 60;

    /** 允许评估器侧覆盖默认 SSE 超时 (毫秒为单位太精细, 秒级足够). */
    public void setSseReadTimeoutSec(int seconds) {
        this.sseReadTimeoutSec = seconds;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String stripTrailSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}