package com.wzh.graph.core;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.wzh.graph.node.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 主对话 Graph 的装配配置 (3.A 升级版).
 *
 * <p><b>Graph 形状 (3.A)</b>:
 * <pre>
 *   __START__
 *      → preprocess          (图片理解, enhancedMessage)
 *      → intent              (意图识别)
 *      → feature_resolve     (三层匹配 + LLM 提取)
 *      → doc_retrieve        (NEW: 调 ProductionRetrieveService)
 *      → merger              (后处理 + 提取 relatedImages/sources)
 *      → mock_answer         (临时, 3.B 删除)
 *      → finalize
 *      → __END__
 * </pre></p>
 *
 * <p><b>3.B 升级预告</b>:
 * <ul>
 *   <li>mock_answer 拆成 knowledge_agent / ticket_agent / admin_agent 三个节点</li>
 *   <li>merger → 三个 agent 之间加 conditionalEdge 分流</li>
 *   <li>intent 后加 chitchat 短路 conditionalEdge → END</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MainGraphConfig {

    private static final String NODE_PREPROCESS = "preprocess";
    private static final String NODE_INTENT = "intent";
    private static final String NODE_FEATURE_RESOLVE = "feature_resolve";
    private static final String NODE_DOC_RETRIEVE = "doc_retrieve";
    private static final String NODE_MERGER = "merger";
    private static final String NODE_MOCK_ANSWER = "mock_answer";
    private static final String NODE_FINALIZE = "finalize";

    private final PreprocessNode preprocessNode;
    private final IntentNode intentNode;
    private final FeatureResolveNode featureResolveNode;
    private final DocRetrieveNode docRetrieveNode;
    private final MergerNode mergerNode;
    private final MockAnswerNode mockAnswerNode;
    private final FinalizeNode finalizeNode;

    @Bean
    public KeyStrategyFactory mainGraphKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> s = new HashMap<>();
            // 输入类
            s.put(GraphStateKeys.USER_MESSAGE, new ReplaceStrategy());
            s.put(GraphStateKeys.USER_ID, new ReplaceStrategy());
            s.put(GraphStateKeys.USER_NAME, new ReplaceStrategy());
            s.put(GraphStateKeys.USER_ROLE, new ReplaceStrategy());
            s.put(GraphStateKeys.SESSION_ID, new ReplaceStrategy());
            s.put(GraphStateKeys.USER_IMAGE_URLS, new ReplaceStrategy());
            s.put(GraphStateKeys.SELECTED_FEATURE_NAME, new ReplaceStrategy());
            // 预处理
            s.put(GraphStateKeys.ENHANCED_MESSAGE, new ReplaceStrategy());
            // 意图
            s.put(GraphStateKeys.INTENT, new ReplaceStrategy());
            s.put(GraphStateKeys.INTENT_SOURCE, new ReplaceStrategy());
            s.put(GraphStateKeys.INTENT_CONFIDENCE, new ReplaceStrategy());
            // Feature
            s.put(GraphStateKeys.MATCHED_FEATURE, new ReplaceStrategy());
            // 检索
            s.put(GraphStateKeys.RETRIEVED_DOC_CHUNKS, new ReplaceStrategy());
            s.put(GraphStateKeys.RETRIEVED_FAQ_CHUNKS, new ReplaceStrategy());
            s.put(GraphStateKeys.RELATED_IMAGES, new ReplaceStrategy());
            s.put(GraphStateKeys.SOURCES, new ReplaceStrategy());
            // 生成
            s.put(GraphStateKeys.FINAL_ANSWER, new ReplaceStrategy());
            // 可观测性
            s.put(GraphStateKeys.PHASE_LATENCIES, new ReplaceStrategy());
            s.put(GraphStateKeys.PHASE_LOG, new ReplaceStrategy());
            return s;
        };
    }

    @Bean
    public CompiledGraph mainGraph(KeyStrategyFactory mainGraphKeyStrategyFactory)
            throws GraphStateException {
        StateGraph graph = new StateGraph("mainGraph", mainGraphKeyStrategyFactory)
                .addNode(NODE_PREPROCESS, node_async(preprocessNode))
                .addNode(NODE_INTENT, node_async(intentNode))
                .addNode(NODE_FEATURE_RESOLVE, node_async(featureResolveNode))
                .addNode(NODE_DOC_RETRIEVE, node_async(docRetrieveNode))
                .addNode(NODE_MERGER, node_async(mergerNode))
                .addNode(NODE_MOCK_ANSWER, node_async(mockAnswerNode))
                .addNode(NODE_FINALIZE, node_async(finalizeNode))
                .addEdge(StateGraph.START, NODE_PREPROCESS)
                .addEdge(NODE_PREPROCESS, NODE_INTENT)
                .addEdge(NODE_INTENT, NODE_FEATURE_RESOLVE)
                .addEdge(NODE_FEATURE_RESOLVE, NODE_DOC_RETRIEVE)
                .addEdge(NODE_DOC_RETRIEVE, NODE_MERGER)
                .addEdge(NODE_MERGER, NODE_MOCK_ANSWER)
                .addEdge(NODE_MOCK_ANSWER, NODE_FINALIZE)
                .addEdge(NODE_FINALIZE, StateGraph.END);

        CompiledGraph compiled = graph.compile();
        log.info("[MainGraphConfig] mainGraph compiled: 7 nodes (3.A 接入真实检索)");
        return compiled;
    }
}