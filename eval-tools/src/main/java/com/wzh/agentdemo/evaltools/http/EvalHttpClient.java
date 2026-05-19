package com.wzh.agentdemo.evaltools.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.evaltools.config.AuditConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 评估器统一 HTTP 客户端 (评估 CI Batch 3 引入).
 *
 * <p>封装对主应用 {@code /internal/eval/**} 端点的调用, 统一处理:
 * <ul>
 *   <li>JSON 序列化/反序列化 (Jackson)</li>
 *   <li>X-Internal-Api-Key 鉴权头注入</li>
 *   <li>HTTP 超时控制 (避免 LLM 兜底慢时单点拖死整个评估流程)</li>
 *   <li>非 200 响应的异常封装</li>
 * </ul>
 *
 * <p><b>未来扩展</b>: Batch 5/6 的 retrieval / latency 端点也复用本类, 不再重复造轮子.</p>
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

    private static String stripTrailSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
