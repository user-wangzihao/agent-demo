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
 * 主对话 Graph 的装配配置 (第四刀升级版).
 *
 * <p><b>Graph 形状 (第四刀)</b>:
 * <pre>
 *   __START__
 *      → preprocess
 *      → intent ──(chitchat 短路)──→ chitchat_answer ──→ finalize → __END__
 *           ↓
 *      → feature_resolve ─┬→ doc_retrieve ─┐
 *                         └→ faq_retrieve ─┴→ merger
 *      → merger ──┬─(admin_meta)──→ admin_agent     ─┐
 *                 ├─(ticket)─────→ ticket_agent     ─┤
 *                 └─(default)────→ knowledge_answer ─┤
 *                                                    ↓
 *                                                 finalize → __END__
 * </pre></p>
 *
 * <p><b>变更点 vs 3.C</b>:
 * <ul>
 *   <li>新增 faq_retrieve 节点 (与 doc_retrieve 并行)</li>
 *   <li>feature_resolve → doc_retrieve / faq_retrieve 两条边触发 fan-out</li>
 *   <li>doc_retrieve / faq_retrieve → merger 两条边自动 fan-in</li>
 * </ul>
 * 并行表达方式参考 Spring AI Alibaba Graph 官方 parallel-node 示例.</p>
 *
 * @author wzh
 * @since 2026-05-13
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MainGraphConfig {

    private static final String NODE_PREPROCESS = "preprocess";
    private static final String NODE_INTENT = "intent";
    private static final String NODE_FEATURE_RESOLVE = "feature_resolve";
    private static final String NODE_DOC_RETRIEVE = "doc_retrieve";
    private static final String NODE_FAQ_RETRIEVE = "faq_retrieve";       // ★ 第四刀新增
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
    private final FaqRetrieveNode faqRetrieveNode;                        // ★ 第四刀新增
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
            // 流式 & 多轮
            s.put(GraphStateKeys.HISTORY_MESSAGES, new ReplaceStrategy());
            // 可观测性 (第六刀 Batch 1):
            // 之所以用 ReplaceStrategy 而非 Append/Merge: CompiledGraph 是 @Bean 单例,
            // 框架内部会跨调用复用 OverAllState, AppendStrategy 会把上次的 phaseLog
            // 一直 concat 下去 (实测: 新 sessionId 的请求 phaseLog 里依然带着上几次的全部记录).
            // 改为 Replace + 节点内 read-modify-write (基类 AbstractGraphNode 实现),
            // 配合 Controller 入口显式 put 空集合, 双保险.
            s.put(GraphStateKeys.PHASE_LATENCIES, new ReplaceStrategy());
            s.put(GraphStateKeys.PHASE_LOG, new ReplaceStrategy());
            // 第六刀 Batch 2 hotfix v4: holder 用 ReplaceStrategy
            // (节点只是往 map 里 put, 不会替换 holder 引用本身)
            s.put(GraphStateKeys.OUTBOUND_CAPTURE, new ReplaceStrategy());
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
                .addNode(NODE_FAQ_RETRIEVE, node_async(faqRetrieveNode))   // ★
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
                // ============ ★ 并行 fan-out: feature_resolve → doc_retrieve / faq_retrieve ============
                .addEdge(NODE_FEATURE_RESOLVE, NODE_DOC_RETRIEVE)
                .addEdge(NODE_FEATURE_RESOLVE, NODE_FAQ_RETRIEVE)
                // ============ ★ 并行 fan-in: doc_retrieve / faq_retrieve → merger ============
                .addEdge(NODE_DOC_RETRIEVE, NODE_MERGER)
                .addEdge(NODE_FAQ_RETRIEVE, NODE_MERGER)
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
        log.info("[MainGraphConfig] mainGraph compiled: 11 nodes, 2 conditionalEdges, "
                + "1 parallel fanout (第四刀: Doc + FAQ 并行检索)");
        return compiled;
    }

    // ==================== ConditionalEdge 判定 (未改动) ====================

    private String routeAfterIntent(OverAllState state) {
        Intent intent = state.value(GraphStateKeys.INTENT, Intent.class).orElse(Intent.DEFAULT);
        if (RouteUtil.isChitchat(intent)) {
            log.info("[route@intent] intent={} → chitchat_answer (short circuit)", intent.getCode());
            return NODE_CHITCHAT_ANSWER;
        }
        return NODE_FEATURE_RESOLVE;
    }

    private String routeAfterMerger(OverAllState state) {
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