package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.common.CacheEntry;
import com.wzh.config.SemanticCacheProperties;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.RouteUtil;
import com.wzh.graph.support.TokenSinkRegistry;
import com.wzh.graph.support.TokenStreamSink;
import com.wzh.service.ProductionRetrieveService;
import com.wzh.service.SemanticCacheService;
import com.wzh.service.SemanticCacheService.CacheLookupResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存查询节点 (第3刀 B3-a 新增).
 *
 * <p><b>位置</b>: intent 之后, feature_resolve 之前. chitchat / admin_command 短路意图不进本节点
 * (这两类意图不应被缓存: chitchat 答案多样化, admin_command 是写操作).</p>
 *
 * <p><b>cacheKey 构成</b>: MD5(featureName + "|" + intent.code + "|" + normalize(query)).
 * featureName 优先用 SELECTED_FEATURE_NAME (前端选择), 没传则调
 * {@link ProductionRetrieveService#resolveFeature(String, String)} 自己解析. 解析出的 featureName
 * 写入 state.MATCHED_FEATURE, 让后续 FeatureResolveNode 检测到已有值时跳过, 避免重复调用.</p>
 *
 * <p><b>命中行为</b>:
 * <ol>
 *   <li>把缓存的 answer / sources / relatedImages 写入 state</li>
 *   <li>state.CACHE_HIT_KEY = cacheKey, state.CACHE_HIT_LAYER = "L1"/"L2"</li>
 *   <li>通过 TokenStreamSink 模拟分块流式推送 answer 给前端 (配置项控制 chunkSize/intervalMs)</li>
 * </ol>
 * 后续条件边 routeAfterCacheCheck 检测 CACHE_HIT_KEY 非 null → 直跳 finalize.</p>
 *
 * <p><b>未命中行为</b>: 仅写入 MATCHED_FEATURE (避免后续重复解析), 不写其他业务字段.
 * 条件边走 feature_resolve 进入完整 RAG 链路.</p>
 *
 * <p><b>开关</b>: properties.enabled=false 时直接 fallthrough (visible as miss).</p>
 *
 * @author wzh
 * @since 2026-05-25 (第3刀 B3-a)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheCheckNode extends AbstractGraphNode {

    private static final String NODE_ID = "cache_check";

    private final SemanticCacheService cacheService;
    private final SemanticCacheProperties properties;
    private final ProductionRetrieveService productionRetrieveService;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state) {
        Map<String, Object> partial = new HashMap<>();

        // 总开关
        if (!properties.isEnabled()) {
            appendPhaseLog(state, partial, "[" + NODE_ID + "] disabled, skip");
            return partial;
        }

        // 取 query + intent
        String enhanced = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class)
                .orElse(state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse(""));
        Intent intent = RouteUtil.safeIntent(state);

        // 解析 featureName: 优先 SELECTED, 否则调 resolveFeature
        String selectedFeature = RouteUtil.safeString(state,
                GraphStateKeys.SELECTED_FEATURE_NAME, null);
        String featureName = selectedFeature;
        if (featureName == null || featureName.isBlank()) {
            try {
                featureName = productionRetrieveService.resolveFeature(enhanced, null);
            } catch (Exception e) {
                log.warn("[{}] resolveFeature failed, will skip cache lookup", NODE_ID, e);
                appendPhaseLog(state, partial, "[" + NODE_ID + "] feature resolve failed → MISS");
                return partial;
            }
        }

        // featureName 仍为空 → 无法算 cacheKey, 直接 miss
        if (featureName == null || featureName.isBlank()) {
            appendPhaseLog(state, partial, "[" + NODE_ID + "] feature=null → MISS");
            log.info("[{}] no featureName resolved → MISS", NODE_ID);
            return partial;
        }

        // 把解析出的 featureName 写回 state, 后续 FeatureResolveNode 据此跳过重复解析
        partial.put(GraphStateKeys.MATCHED_FEATURE, featureName);

        // 查缓存
        CacheLookupResult result = cacheService.lookup(enhanced, featureName, intent);
        if (!result.isHit()) {
            log.info("[{}] feature='{}' intent={} → MISS (will proceed to RAG)",
                    NODE_ID, featureName, intent.getCode());
            appendPhaseLog(state, partial,
                    "[" + NODE_ID + "] feature=" + featureName + " intent=" + intent.getCode() + " → MISS");
            return partial;
        }

        // ============ 命中: 写 state + 模拟流式推送 ============
        CacheEntry entry = result.getEntry();
        partial.put(GraphStateKeys.FINAL_ANSWER, entry.getAnswerText());
        partial.put(GraphStateKeys.SOURCES,
                entry.getSources() == null ? Collections.emptyList() : entry.getSources());
        partial.put(GraphStateKeys.RELATED_IMAGES,
                entry.getRelatedImages() == null ? Collections.emptyList() : entry.getRelatedImages());
        partial.put(GraphStateKeys.CACHE_HIT_KEY, result.getCacheKey());
        partial.put(GraphStateKeys.CACHE_HIT_LAYER, result.getHitLayer());

        // 模拟分块流式
        replayAnswerAsStream(state, entry.getAnswerText());

        log.info("[{}] HIT layer={} cacheKey={} similarity={} answerLen={} sources={}",
                NODE_ID, result.getHitLayer(), result.getCacheKey(),
                result.getSimilarity() == null ? "-" : String.format("%.4f", result.getSimilarity()),
                entry.getAnswerText().length(),
                entry.getSources() == null ? 0 : entry.getSources().size());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] HIT layer=" + result.getHitLayer()
                        + " key=" + result.getCacheKey()
                        + " feature=" + featureName
                        + " intent=" + intent.getCode());
        return partial;
    }

    /**
     * 把缓存的 answer 按配置的分块大小 + 间隔, 通过 TokenStreamSink 推给前端,
     * 模拟真实 LLM 流式输出的体验.
     */
    private void replayAnswerAsStream(OverAllState state, String answer) {
        if (answer == null || answer.isEmpty()) return;

        String execId = state.value(TokenSinkRegistry.EXECUTION_ID_KEY, String.class).orElse(null);
        TokenStreamSink sink = TokenSinkRegistry.get(execId);
        if (sink == TokenStreamSink.NOOP) {
            log.debug("[{}] no sink bound, skip replay", NODE_ID);
            return;
        }

        int chunkSize = Math.max(1, properties.getReplayChunkSize());
        int sleepMs = Math.max(0, properties.getReplayChunkIntervalMs());

        try {
            for (int i = 0; i < answer.length(); i += chunkSize) {
                int end = Math.min(i + chunkSize, answer.length());
                sink.onToken(answer.substring(i, end));
                if (sleepMs > 0 && end < answer.length()) {
                    Thread.sleep(sleepMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] replay interrupted", NODE_ID);
        }
    }
}