package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.service.MilvusService.SearchResult;
import com.wzh.service.ProductionRetrieveService;
import com.wzh.service.intent.IntentBoostUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档检索节点.
 *
 * <p><b>职责</b>: 基于已解析的 matchedFeature 调用生产侧检索流水线, 拿到文档 chunk 列表.</p>
 *
 * <p><b>检索流水线</b>: 直接复用 ProductionRetrieveService.retrieveByMatchedFeature():
 * <ul>
 *   <li>matchedFeature 非空 → feature_aware 检索 (最高质量, MRR@5=0.9042)</li>
 *   <li>未命中 → fallback (rewriting + RRF + reranker)</li>
 *   <li>异常 → baseline 兜底</li>
 * </ul>
 * Graph 模式下不需要重新发明检索, 把这套复用即可.</p>
 *
 * <p><b>IntentBoost</b>: 检索完成后, 用 IntentBoostUtil 按意图加权 chunk_type,
 * 让对应类型的 chunk 排序靠前. 和 AgentService 行为一致.</p>
 *
 * <p><b>不做后处理</b>: 分数过滤 / 分桶 / 整合等后处理逻辑放到下游 MergerNode 里
 * (MergerNode 是"准备生成上下文"的统一入口, 第四刀 FAQ 加入后也会经过它).</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocRetrieveNode extends AbstractGraphNode {

    private static final String NODE_ID = "doc_retrieve";

    private final ProductionRetrieveService productionRetrieveService;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state) {
        String enhancedMessage = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class).orElse("");
        String matchedFeature = state.value(GraphStateKeys.MATCHED_FEATURE, String.class).orElse(null);
        Intent intent = state.value(GraphStateKeys.INTENT, Intent.class).orElse(Intent.DEFAULT);

        // 调用复用现有流水线, 跳过 resolveFeature
        List<SearchResult> rawResults = productionRetrieveService.retrieveByMatchedFeature(
                enhancedMessage, matchedFeature);

        // IntentBoost: 按意图加权 chunk_type
        IntentBoostUtil.applyBoost(rawResults, intent);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.RETRIEVED_DOC_CHUNKS, rawResults);

        log.info("[{}] matched={} intent={} retrieved={} (raw, 未后处理)",
                NODE_ID, matchedFeature, intent.getCode(), rawResults.size());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] doc=" + rawResults.size()
                        + " matched=" + matchedFeature);
        return partial;
    }
}