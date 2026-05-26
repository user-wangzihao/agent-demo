package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.GraphMetricsCollector;
import com.wzh.graph.support.RouteUtil;
import com.wzh.service.MilvusService.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收尾节点.
 *
 * <p><b>职责</b>: 不写任何业务字段, 只输出一行总结日志, 便于在控制台一眼看清整条 Graph 的执行情况.</p>
 *
 * <p><b>第三刀升级</b>: 在这里把 assistant message 写入 chat_message 表 (对齐
 * AgentService.saveAssistantMessageAndComplete()), 并触发 SSE done 事件.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalizeNode extends AbstractGraphNode {

    private static final String NODE_ID = "finalize";

    /** B2 引入: 业务指标采集器. 在 doApply 末尾把 state 里现成的观测数据桥接到 Prometheus. */
    private final GraphMetricsCollector metricsCollector;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        Map<String, Long> latencies = state.<Map<String, Long>>value(GraphStateKeys.PHASE_LATENCIES)
                .orElse(new HashMap<>());
        List<String> log = state.<List<String>>value(GraphStateKeys.PHASE_LOG)
                .orElse(List.of());
        String finalAnswer = state.value(GraphStateKeys.FINAL_ANSWER, String.class).orElse("(empty)");

        // 第3刀 B3-a: 缓存命中分支
        String cacheHitLayer = state.value(GraphStateKeys.CACHE_HIT_LAYER, String.class).orElse(null);
        boolean cacheHit = cacheHitLayer != null && !cacheHitLayer.isBlank();

        long total = latencies.values().stream().mapToLong(Long::longValue).sum();
        FinalizeNode.log.info("[{}] ==== Graph 执行完毕 ====", NODE_ID);
        FinalizeNode.log.info("[{}] 总耗时: {}ms; 各节点: {}; cacheHit={}",
                NODE_ID, total, latencies, cacheHit ? cacheHitLayer : "false");
        FinalizeNode.log.info("[{}] 处理流程:", NODE_ID);
        for (String line : log) {
            FinalizeNode.log.info("[{}]   - {}", NODE_ID, line);
        }
        FinalizeNode.log.info("[{}] 最终回答 ({} chars): {}", NODE_ID,
                finalAnswer.length(),
                finalAnswer.length() > 100 ? finalAnswer.substring(0, 100) + "..." : finalAnswer);

        // ==================== B2: Prometheus 指标桥接 ====================
        // B3-a: 缓存命中时跳过 intent/retrieval 部分指标 (这些节点未执行, state 字段也为空).
        // 节点耗时 (recordAllNodeLatencies) 仍然桥接 — phaseLatencies 里只会有真实跑过的节点.
        try {
            metricsCollector.recordAllNodeLatencies(latencies);

            if (!cacheHit) {
                Intent intent = RouteUtil.safeIntent(state);
                String intentSource = RouteUtil.safeString(state, GraphStateKeys.INTENT_SOURCE, "unknown");
                metricsCollector.recordIntent(intent, intentSource);

                List<SearchResult> docChunks = (List<SearchResult>) state
                        .value(GraphStateKeys.RETRIEVED_DOC_CHUNKS).orElse(Collections.emptyList());
                List<SearchResult> faqChunks = (List<SearchResult>) state
                        .value(GraphStateKeys.RETRIEVED_FAQ_CHUNKS).orElse(Collections.emptyList());
                metricsCollector.recordRetrievedDocChunks(docChunks.size());
                metricsCollector.recordRetrievedFaqChunks(faqChunks.size());
            }
        } catch (Exception e) {
            FinalizeNode.log.warn("[{}] metrics 桥接失败 (不影响主流程)", NODE_ID, e);
        }

        Map<String, Object> partial = new HashMap<>();
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] graph 执行完毕, 总耗时 " + total + "ms"
                        + (cacheHit ? " (cache HIT " + cacheHitLayer + ")" : ""));
        return partial;
    }
}