package com.wzh.controller;

import com.wzh.model.intent.IntentClassificationResult;
import com.wzh.service.intent.IntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评估 CI 专用 internal 端点 (评估 CI 主线 Batch 3 引入).
 *
 * <p><b>定位</b>: 供 eval-tools 模块 (独立 jar) 通过 HTTP 调用主应用的内部能力,
 * 实现"评估器与生产代码编译期隔离, 运行期通过 HTTP 解耦"的架构.</p>
 *
 * <p><b>与既有 RagEvalController 的区别</b>:
 * <ul>
 *   <li>{@code RagEvalController} (零改动, 不碰): 业务侧 RAG 评估编排, 走 /api/rag-eval/**,
 *       用 token 鉴权, 服务于前端面板</li>
 *   <li>{@code InternalEvalController} (本类, 新增): 评估 CI 数据回传端点, 走 /internal/eval/**,
 *       用 X-Internal-Api-Key 鉴权, 服务于离线评估器</li>
 * </ul>
 *
 * <p><b>路径规约</b>: {@code /internal/eval/**} 命名空间, 与既有 {@code /internal/learning/**}
 * 并列. 这类路径在 {@code WebMvcConfig.AuthInterceptor} 中自动绕过 token 拦截
 * (拦截器只匹配 {@code /api/**}), 由本 controller 自己用 X-Internal-Api-Key 鉴权.</p>
 *
 * <p><b>预留扩展点</b>: 评估 CI Batch 5 (检索质量) / Batch 6 (端到端延迟) 后续可能在
 * 本 controller 加新端点 (如 /internal/eval/retrieval 等). 当前 Batch 3 仅暴露
 * 意图分类入口.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 3)
 */
@Slf4j
@RestController
@RequestMapping("/internal/eval")
@RequiredArgsConstructor
public class InternalEvalController {

    private final IntentClassifier intentClassifier;

    @Value("${internal.api-key:internal-secret-key-change-me}")
    private String expectedApiKey;

    /**
     * 评估端点: 单条 query 的意图分类.
     *
     * <p><b>输入</b>: JSON body, 形如 {@code {"query": "你好"}}. query 非空字符串.</p>
     *
     * <p><b>输出</b>: 200 OK, body 字段:
     * <ul>
     *   <li>{@code intent}     - 意图 code (chitchat / how_to / troubleshoot / feature_intro
     *                            / admin_command / default), 与 Intent.code 一致</li>
     *   <li>{@code source}     - 分类来源 (KEYWORD / LLM / FALLBACK)</li>
     *   <li>{@code confidence} - 置信度 0.0~1.0</li>
     *   <li>{@code reasoning}  - 分类理由 (debug 用)</li>
     *   <li>{@code elapsedMs}  - 本次分类耗时</li>
     * </ul>
     *
     * <p><b>错误码</b>: 401 (api key 错) / 400 (query 缺失) / 500 (分类器异常,
     * 但 HybridIntentClassifier 设计上永不抛异常, 此分支防御性兜底).</p>
     *
     * <p><b>幂等 & 无副作用</b>: 仅调用 IntentClassifier.classify, 不写库不发消息,
     * 可安全并发调用. 评估器并发跑 21 条用例时无需额外控流.</p>
     */
    @PostMapping("/intent")
    public ResponseEntity<Map<String, Object>> classifyIntent(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey
    ) {
        // ---- 1. 鉴权 (与 InternalLearningController 同款) ----
        if (!expectedApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false, "message", "无效的内部 API Key"));
        }

        // ---- 2. 参数校验 ----
        Object queryObj = body == null ? null : body.get("query");
        String query = queryObj == null ? null : String.valueOf(queryObj);
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(400).body(Map.of(
                    "success", false, "message", "query 不能为空"));
        }

        // ---- 3. 调既有分类器, 不改写任何业务逻辑 ----
        long t0 = System.currentTimeMillis();
        IntentClassificationResult result;
        try {
            result = intentClassifier.classify(query);
        } catch (Exception e) {
            // 防御性兜底: HybridIntentClassifier 契约是不抛异常, 但仍兜底防 Spring DI 异常
            log.error("[InternalEval/intent] 分类器异常 query='{}'", query, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "分类器内部异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        long elapsed = System.currentTimeMillis() - t0;

        // ---- 4. 序列化返回 ----
        // 用 LinkedHashMap 保证字段顺序稳定, 评估器侧解析时日志更可读.
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("intent", result.getIntent() == null ? "default" : result.getIntent().getCode());
        resp.put("source", result.getSource() == null ? null : result.getSource().name());
        resp.put("confidence", result.getConfidence());
        resp.put("reasoning", result.getReasoning());
        resp.put("elapsedMs", elapsed);

        log.debug("[InternalEval/intent] query='{}' → intent={} source={} conf={} ({}ms)",
                query, resp.get("intent"), resp.get("source"), resp.get("confidence"), elapsed);

        return ResponseEntity.ok(resp);
    }
}
