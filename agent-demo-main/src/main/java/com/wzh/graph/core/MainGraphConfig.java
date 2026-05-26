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
 * 主对话 Graph 的装配配置 (第3刀 B3-a 升级版).
 *
 * <p><b>Graph 形状 (B3-a)</b>:
 * <pre>
 *   __START__
 *      → preprocess
 *      → intent ──(chitchat 短路)──→ chitchat_answer ────────────────┐
 *           │                                                          │
 *           ├──(admin_command + admin 短路)──→ admin_agent ────────────┤
 *           │                                                          │
 *           ↓ (业务意图)                                                │
 *      → cache_check ──(命中)──→ finalize 短路                          │
 *           │                                                          │
 *           ↓ (未命中)                                                  │
 *      → feature_resolve ─┬→ doc_retrieve ─┐                           │
 *                         └→ faq_retrieve ─┴→ merger                   │
 *      → merger ──┬─(ticket)─────→ ticket_agent ─────────────────────┤
 *                 └─(default)────→ knowledge_answer ──────────────────┤
 *                                                                      ↓
 *                                                                   finalize → __END__
 * </pre></p>
 *
 * <p><b>变更点 vs B5-b-1 (第六刀)</b>:
 * <ul>
 *   <li>新增 cache_check 节点, 位置: intent 之后, feature_resolve 之前.</li>
 *   <li>新增 conditionalEdge routeAfterCacheCheck: 命中 → finalize, 未命中 → feature_resolve.</li>
 *   <li>chitchat / admin_command 短路保持不变, 这两类意图不进 cache_check
 *       (不应被缓存: chitchat 答案多样化, admin_command 是写操作).</li>
 *   <li>原 routeAfterIntent 的 "进 RAG" 出边从 feature_resolve 改为 cache_check.</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-25 (第3刀 B3-a)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MainGraphConfig {

    private static final String NODE_PREPROCESS = "preprocess";
    private static final String NODE_INTENT = "intent";
    private static final String NODE_CACHE_CHECK = "cache_check";          // ★ 第3刀 B3-a 新增
    private static final String NODE_FEATURE_RESOLVE = "feature_resolve";
    private static final String NODE_DOC_RETRIEVE = "doc_retrieve";
    private static final String NODE_FAQ_RETRIEVE = "faq_retrieve";
    private static final String NODE_MERGER = "merger";
    private static final String NODE_CHITCHAT_ANSWER = "chitchat_answer";
    private static final String NODE_KNOWLEDGE_ANSWER = "knowledge_answer";
    private static final String NODE_TICKET_AGENT = "ticket_agent";
    private static final String NODE_ADMIN_AGENT = "admin_agent";
    private static final String NODE_FINALIZE = "finalize";

    private final PreprocessNode preprocessNode;
    private final IntentNode intentNode;
    private final CacheCheckNode cacheCheckNode;                            // ★
    private final FeatureResolveNode featureResolveNode;
    private final DocRetrieveNode docRetrieveNode;
    private final FaqRetrieveNode faqRetrieveNode;
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
            // 缓存 (第3刀 B3-a)
            s.put(GraphStateKeys.CACHE_HIT_KEY, new ReplaceStrategy());
            s.put(GraphStateKeys.CACHE_HIT_LAYER, new ReplaceStrategy());
            // 流式 & 多轮
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
                .addNode(NODE_CACHE_CHECK, node_async(cacheCheckNode))                 // ★
                .addNode(NODE_FEATURE_RESOLVE, node_async(featureResolveNode))
                .addNode(NODE_DOC_RETRIEVE, node_async(docRetrieveNode))
                .addNode(NODE_FAQ_RETRIEVE, node_async(faqRetrieveNode))
                .addNode(NODE_MERGER, node_async(mergerNode))
                .addNode(NODE_CHITCHAT_ANSWER, node_async(chitchatAnswerNode))
                .addNode(NODE_KNOWLEDGE_ANSWER, node_async(knowledgeAnswerNode))
                .addNode(NODE_TICKET_AGENT, node_async(ticketAgentNode))
                .addNode(NODE_ADMIN_AGENT, node_async(adminAgentNode))
                .addNode(NODE_FINALIZE, node_async(finalizeNode))
                // ============ 入口边 ============
                .addEdge(StateGraph.START, NODE_PREPROCESS)
                .addEdge(NODE_PREPROCESS, NODE_INTENT)
                // ============ 分流 ① intent 之后: chitchat / admin_command / 进 cache_check ============
                .addConditionalEdges(
                        NODE_INTENT,
                        edge_async(this::routeAfterIntent),
                        Map.of(
                                NODE_CHITCHAT_ANSWER, NODE_CHITCHAT_ANSWER,
                                NODE_ADMIN_AGENT, NODE_ADMIN_AGENT,
                                NODE_CACHE_CHECK, NODE_CACHE_CHECK              // ★ 改: 原是 feature_resolve
                        ))
                // ============ 分流 ② cache_check 之后: 命中 → finalize / 未命中 → feature_resolve ============
                .addConditionalEdges(
                        NODE_CACHE_CHECK,
                        edge_async(this::routeAfterCacheCheck),
                        Map.of(
                                NODE_FINALIZE, NODE_FINALIZE,
                                NODE_FEATURE_RESOLVE, NODE_FEATURE_RESOLVE
                        ))
                // ============ 并行 fan-out: feature_resolve → doc_retrieve / faq_retrieve ============
                .addEdge(NODE_FEATURE_RESOLVE, NODE_DOC_RETRIEVE)
                .addEdge(NODE_FEATURE_RESOLVE, NODE_FAQ_RETRIEVE)
                // ============ 并行 fan-in: doc_retrieve / faq_retrieve → merger ============
                .addEdge(NODE_DOC_RETRIEVE, NODE_MERGER)
                .addEdge(NODE_FAQ_RETRIEVE, NODE_MERGER)
                // ============ 分流 ③ merger 之后: ticket / knowledge ============
                .addConditionalEdges(
                        NODE_MERGER,
                        edge_async(this::routeAfterMerger),
                        Map.of(
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
        log.info("[MainGraphConfig] mainGraph compiled: 12 nodes, 3 conditionalEdges "
                + "(routeAfterIntent=3-way / routeAfterCacheCheck=2-way / routeAfterMerger=2-way), "
                + "1 parallel fanout (Doc + FAQ 并行检索)");
        return compiled;
    }

    // ==================== ConditionalEdge 判定 ====================

    private String routeAfterIntent(OverAllState state) {
        Intent intent = RouteUtil.safeIntent(state);
        String userRole = RouteUtil.safeString(state, GraphStateKeys.USER_ROLE, "user");

        if (RouteUtil.isChitchat(intent)) {
            log.info("[route@intent] intent={} → chitchat_answer (short circuit)", intent.getCode());
            return NODE_CHITCHAT_ANSWER;
        }
        if (RouteUtil.isAdminCommand(intent, userRole)) {
            log.info("[route@intent] intent={} userRole={} → admin_agent (short circuit, skip RAG)",
                    intent.getCode(), userRole);
            return NODE_ADMIN_AGENT;
        }
        // 业务意图 → 走 cache_check, 命中跳 finalize, 未命中走完整 RAG
        log.info("[route@intent] intent={} userRole={} → cache_check",
                intent.getCode(), userRole);
        return NODE_CACHE_CHECK;
    }

    /** cache_check 之后: state.CACHE_HIT_KEY 非空 = 命中, 跳 finalize; 否则进 RAG. */
    private String routeAfterCacheCheck(OverAllState state) {
        String hitKey = RouteUtil.safeString(state, GraphStateKeys.CACHE_HIT_KEY, null);
        if (hitKey != null && !hitKey.isBlank()) {
            log.info("[route@cache_check] HIT → finalize (skip RAG)");
            return NODE_FINALIZE;
        }
        log.info("[route@cache_check] MISS → feature_resolve");
        return NODE_FEATURE_RESOLVE;
    }

    private String routeAfterMerger(OverAllState state) {
        String query = RouteUtil.safeString(state, GraphStateKeys.ENHANCED_MESSAGE,
                RouteUtil.safeString(state, GraphStateKeys.USER_MESSAGE, ""));
        Intent intent = RouteUtil.safeIntent(state);

        if (RouteUtil.isTicketIntent(query, intent)) {
            log.info("[route@merger] query matched ticket pattern → ticket_agent");
            return NODE_TICKET_AGENT;
        }
        log.info("[route@merger] default → knowledge_answer");
        return NODE_KNOWLEDGE_ANSWER;
    }
}