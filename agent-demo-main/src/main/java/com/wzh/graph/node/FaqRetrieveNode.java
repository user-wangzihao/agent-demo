package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.config.FaqRetrieveProperties;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.service.DashScopeService;
import com.wzh.service.FaqMilvusService;
import com.wzh.service.MilvusService.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FAQ 检索节点 (第四刀引入).
 *
 * <p><b>与 DocRetrieveNode 并行</b>: 在 Graph 装配里通过
 * <pre>
 *   .addEdge(NODE_FEATURE_RESOLVE, NODE_DOC_RETRIEVE)
 *   .addEdge(NODE_FEATURE_RESOLVE, NODE_FAQ_RETRIEVE)
 * </pre>
 * 触发 fan-out, 两个节点并行执行, MergerNode 处自动 fan-in.</p>
 *
 * <p><b>检索策略 (第一阶段刻意简化)</b>:
 * <ul>
 *   <li>单路向量检索, 不接 reranker / rewriting / RRF (FAQ 量级小, 简单足够)</li>
 *   <li>过滤策略:
 *     <ul>
 *       <li>matchedFeature 非空 → feature_name in [matchedFeature, "通用FAQ"]</li>
 *       <li>matchedFeature 为空 → feature_name == "通用FAQ" (只召回通用)</li>
 *     </ul>
 *   </li>
 *   <li>异常/开关关闭 → 返回空 List, 不影响主流程</li>
 * </ul></p>
 *
 * <p><b>FAQ 库未来扩展空间</b>: 若 FAQ 量级到 1000+ 条且召回精度问题暴露,
 * 可演进加 reranker / Query Rewriting. 当前阶段保持简单.</p>
 *
 * @author wzh
 * @since 2026-05-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaqRetrieveNode extends AbstractGraphNode {

    private static final String NODE_ID = "faq_retrieve";

    private final DashScopeService dashScopeService;
    private final FaqMilvusService faqMilvusService;
    private final FaqRetrieveProperties faqRetrieveProperties;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state) {
        Map<String, Object> partial = new HashMap<>();

        // 开关关闭 → 空 List 占位
        if (!faqRetrieveProperties.isEnabled()) {
            partial.put(GraphStateKeys.RETRIEVED_FAQ_CHUNKS, Collections.emptyList());
            log.info("[{}] disabled, skip", NODE_ID);
            appendPhaseLog(state, partial, "[" + NODE_ID + "] disabled");
            return partial;
        }

        String enhancedMessage = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class).orElse("");
        String matchedFeature = state.value(GraphStateKeys.MATCHED_FEATURE, String.class).orElse(null);

        List<SearchResult> results;
        try {
            List<Float> vector = dashScopeService.getEmbedding(enhancedMessage);
            if (vector == null || vector.isEmpty()) {
                log.warn("[{}] embedding 为空, 返回空结果", NODE_ID);
                results = Collections.emptyList();
            } else {
                List<String> features = buildFeatureFilter(matchedFeature);
                results = faqMilvusService.searchFaq(vector, features, faqRetrieveProperties.getTopK());
            }
        } catch (Exception e) {
            log.error("[{}] 检索异常, 降级空 List", NODE_ID, e);
            results = Collections.emptyList();
        }

        partial.put(GraphStateKeys.RETRIEVED_FAQ_CHUNKS, results);

        log.info("[{}] matched={} retrieved={}",
                NODE_ID, matchedFeature, results.size());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] faq=" + results.size()
                        + " matched=" + matchedFeature);
        return partial;
    }

    /**
     * 构造 feature_name 过滤列表.
     *
     * <p>matchedFeature 非空 → [matchedFeature, "通用FAQ"]<br>
     * matchedFeature 为空   → ["通用FAQ"] (宽泛 query 只召回通用 FAQ)</p>
     */
    private List<String> buildFeatureFilter(String matchedFeature) {
        String general = faqRetrieveProperties.getGeneralMarker();
        List<String> features = new ArrayList<>();
        if (matchedFeature != null && !matchedFeature.trim().isEmpty()) {
            features.add(matchedFeature);
        }
        features.add(general);
        return features;
    }
}