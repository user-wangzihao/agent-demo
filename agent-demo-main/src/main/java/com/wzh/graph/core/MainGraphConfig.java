package com.wzh.graph.core;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.wzh.enums.Intent;
import com.wzh.graph.node.*;
import com.wzh.graph.support.RouteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 主对话 Graph 的装配配置 (3.B 升级版).
 *
 * <p><b>Graph 形状 (3.B)</b>:
 * <pre>
 *   __START__
 *      → preprocess
 *      → intent ──(chitchat 短路)──→ chitchat_answer ──→ finalize → __END__
 *           ↓
 *      → feature_resolve
 *      → doc_retrieve
 *      → merger ──┬─(admin_meta)──→ admin_agent     ─┐
 *                 ├─(ticket)─────→ ticket_agent     ─┤
 *                 └─(default)────→ knowledge_answer ─┤
 *                                                    ↓
 *                                                 finalize → __END__
 * </pre></p>
 *
 * <p><b>变更点 vs 3.A</b>:
 * <ul>
 *   <li>新增 4 个 answer 类节点: chitchat / knowledge / ticket / admin</li>
 *   <li>mock_answer 装配移除 (文件保留, 第六刀彻底删)</li>
 *   <li>新增 2 处 conditionalEdge</li>
 *   <li>分流判定全部下沉到 {@link RouteUtil}</li>
 * </ul></p>
 *
 * <p><b>3.C 升级预告</b>: SSE 流式输出, 用 CompiledGraph.stream() + Flux 订阅.</p>
 *
 * @author wzh
 * @since 2026-05-12
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
    private static final String NODE_CHITCHAT_ANSWER = "chitchat_answer";
    private static final String NODE_KNOWLEDGE_ANSWER = "knowledge_answer";
    private static final String NODE_TICKET_AGENT = "ticket_agent";
    private static final String NODE_ADMIN_AGENT = "admin_agent";
    private static final String NODE_FINALIZE = "finalize";

    private final PreprocessNode preprocessNode;
    private final IntentNode intentNode;
    private final FeatureResolveNode featureResolveNode;
    private final DocRetrieveNode docRetrieveNode;
    private final MergerNode mergerNode;
    private final ChitchatAnswerNode chitchatAnswerNode;
    private final KnowledgeAnswerNode knowledgeAnswerNode;
    private final TicketAgentNode ticketAgentNode;
    private final AdminAgentNode adminAgentNode;
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
            // 3.C 流式 & 多轮
            s.put(GraphStateKeys.HISTORY_MESSAGES, new ReplaceStrategy());
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
                // ============ 节点装配 ============
                .addNode(NODE_PREPROCESS, node_async(preprocessNode))
                .addNode(NODE_INTENT, node_async(intentNode))
                .addNode(NODE_FEATURE_RESOLVE, node_async(featureResolveNode))
                .addNode(NODE_DOC_RETRIEVE, node_async(docRetrieveNode))
                .addNode(NODE_MERGER, node_async(mergerNode))
                .addNode(NODE_CHITCHAT_ANSWER, node_async(chitchatAnswerNode))
                .addNode(NODE_KNOWLEDGE_ANSWER, node_async(knowledgeAnswerNode))
                .addNode(NODE_TICKET_AGENT, node_async(ticketAgentNode))
                .addNode(NODE_ADMIN_AGENT, node_async(adminAgentNode))
                .addNode(NODE_FINALIZE, node_async(finalizeNode))
                // ============ 入口边 ============
                .addEdge(StateGraph.START, NODE_PREPROCESS)
                .addEdge(NODE_PREPROCESS, NODE_INTENT)
                // ============ 分流 ① intent 之后: chitchat 短路 ============
                .addConditionalEdges(
                        NODE_INTENT,
                        edge_async(this::routeAfterIntent),
                        Map.of(
                                NODE_CHITCHAT_ANSWER, NODE_CHITCHAT_ANSWER,
                                NODE_FEATURE_RESOLVE, NODE_FEATURE_RESOLVE
                        ))
                // ============ 主链路 ============
                .addEdge(NODE_FEATURE_RESOLVE, NODE_DOC_RETRIEVE)
                .addEdge(NODE_DOC_RETRIEVE, NODE_MERGER)
                // ============ 分流 ② merger 之后: 三选一 ============
                .addConditionalEdges(
                        NODE_MERGER,
                        edge_async(this::routeAfterMerger),
                        Map.of(
                                NODE_ADMIN_AGENT, NODE_ADMIN_AGENT,
                                NODE_TICKET_AGENT, NODE_TICKET_AGENT,
                                NODE_KNOWLEDGE_ANSWER, NODE_KNOWLEDGE_ANSWER
                        ))
                // ============ 汇聚到 finalize ============
                .addEdge(NODE_CHITCHAT_ANSWER, NODE_FINALIZE)
                .addEdge(NODE_KNOWLEDGE_ANSWER, NODE_FINALIZE)
                .addEdge(NODE_TICKET_AGENT, NODE_FINALIZE)
                .addEdge(NODE_ADMIN_AGENT, NODE_FINALIZE)
                .addEdge(NODE_FINALIZE, StateGraph.END);

        CompiledGraph compiled = graph.compile();
        log.info("[MainGraphConfig] mainGraph compiled: 10 nodes, 2 conditionalEdges (3.B 真实 Multi-Agent 骨架, 做法 X)");
        return compiled;
    }

    // ==================== ConditionalEdge 判定 ====================

    /**
     * intent 之后的分流: chitchat → 短路, 其他 → 继续主链路.
     */
    private String routeAfterIntent(OverAllState state) {
        Intent intent = state.value(GraphStateKeys.INTENT, Intent.class).orElse(Intent.DEFAULT);
        if (RouteUtil.isChitchat(intent)) {
            log.info("[route@intent] intent={} → chitchat_answer (short circuit)", intent.getCode());
            return NODE_CHITCHAT_ANSWER;
        }
        return NODE_FEATURE_RESOLVE;
    }

    /**
     * merger 之后的分流: admin_meta > ticket > knowledge (默认兜底).
     *
     * <p><b>判定优先级</b> (高 → 低): admin_meta_query > ticket_intent > knowledge_answer.</p>
     */
    private String routeAfterMerger(OverAllState state) {
        // 优先用 enhancedMessage (含图片描述), 没有就 fallback userMessage
        String query = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class)
                .orElse(state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse(""));
        String userRole = state.value(GraphStateKeys.USER_ROLE, String.class).orElse("user");
        Intent intent = state.value(GraphStateKeys.INTENT, Intent.class).orElse(Intent.DEFAULT);

        if (RouteUtil.isAdminMetaQuery(query, userRole)) {
            log.info("[route@merger] userRole={} → admin_agent", userRole);
            return NODE_ADMIN_AGENT;
        }
        if (RouteUtil.isTicketIntent(query, intent)) {
            log.info("[route@merger] query matched ticket pattern → ticket_agent");
            return NODE_TICKET_AGENT;
        }
        log.info("[route@merger] default → knowledge_answer");
        return NODE_KNOWLEDGE_ANSWER;
    }
}