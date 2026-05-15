package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.service.ProductionRetrieveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Feature 解析节点 (3.A 升级版).
 *
 * <p><b>3.A 升级</b>: 直接调 ProductionRetrieveService.resolveFeature(), 实现完整三层匹配 +
 * LLM 提取链路. 行为对齐 AgentService 老链路, 但执行位置从 ProductionRetrieveService 内部
 * 提升到 Graph 节点, 让流程显式可观察.</p>
 *
 * <p><b>关键设计点</b>: 这里解析出的 matchedFeature 会被 DocRetrieveNode 直接复用 (调
 * retrieveByMatchedFeature 跳过重复解析). 这就是把 resolveFeature 提升为节点的意义 -
 * 让架构语义对齐 Graph 设计.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureResolveNode extends AbstractGraphNode {

    private static final String NODE_ID = "feature_resolve";

    private final ProductionRetrieveService productionRetrieveService;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state) {
        // 用 enhancedMessage (图片描述拼接后的) 做 feature 解析, 和 AgentService 行为一致
        String enhancedMessage = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class).orElse("");
        String selectedFeatureName = state.value(GraphStateKeys.SELECTED_FEATURE_NAME, String.class).orElse(null);

        String matched = productionRetrieveService.resolveFeature(enhancedMessage, selectedFeatureName);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.MATCHED_FEATURE, matched);

        // 第六刀 Batch 2 hotfix v4: 把 matchedFeature 写进 outboundCapture holder.
        @SuppressWarnings("unchecked")
        Map<String, Object> outbound = (Map<String, Object>) state
                .value(GraphStateKeys.OUTBOUND_CAPTURE).orElse(null);
        if (outbound != null && matched != null) {
            outbound.put("matchedFeature", matched);
        }

        log.info("[{}] selected='{}' → matched={}", NODE_ID, selectedFeatureName, matched);
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] matched=" + matched);
        return partial;
    }
}