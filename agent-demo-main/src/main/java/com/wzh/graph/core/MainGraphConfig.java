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
 * 主对话 Graph 的装配配置 (B5-b-1 升级版).
 *
 * <p><b>Graph 形状 (B5-b-1)</b>:
 * <pre>
 *   __START__
 *      → preprocess
 *      → intent ──(chitchat 短路)──→ chitchat_answer ──┐
 *           │                                          │
 *           ├──(admin_command + admin 短路)──→ admin_agent ──┤
 *           │                                          │
 *           ↓                                          │
 *      → feature_resolve ─┬→ doc_retrieve ─┐           │
 *                         └→ faq_retrieve ─┴→ merger   │
 *      → merger ──┬─(ticket)─────→ ticket_agent     ─┤
 *                 └─(default)────→ knowledge_answer ─┤
 *                                                    ↓
 *                                                 finalize → __END__
 * </pre></p>
 *
 * <p><b>变更点 vs 第四刀</b>:
 * <ul>
 *   <li>routeAfterIntent 从二分流升级为三分流 — admin_command 在 intent 阶段直接短路到
 *       admin_agent, 不走 RAG. 这把"管理员意图"从正则关键词后置判定 (做法 X) 升级为
 *       LLM/关键词分类一等公民 (做法 Y), 路由层只做映射不做判断.</li>
 *   <li>routeAfterMerger 简化为 ticket / knowledge 二选一, isAdminMetaQuery 路径已退役.</li>
 *   <li>所有 state 取值走 RouteUtil.safeIntent / safeString, 修复 Graph 1.1.2 把枚举
 *       序列化为 ArrayList 导致 state.value(K, Intent.class) 静默 fallback DEFAULT 的潜伏 bug.
 *       影响范围: chitchat 短路、IntentBoost 加权、SystemPrompt 风格模板, 此前全部未生效.</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-13 (第四刀); 2026-05-19 (B5-b-1 重构)
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
                // ============ 分流 ① intent 之后: chitchat 短路 / admin_command 短路 / 进 RAG ============
                .addConditionalEdges(
                        NODE_INTENT,
                        edge_async(this::routeAfterIntent),
                        Map.of(
                                NODE_CHITCHAT_ANSWER, NODE_CHITCHAT_ANSWER,
                                NODE_ADMIN_AGENT, NODE_ADMIN_AGENT,
                                NODE_FEATURE_RESOLVE, NODE_FEATURE_RESOLVE
                        ))
                // ============ ★ 并行 fan-out: feature_resolve → doc_retrieve / faq_retrieve ============
                .addEdge(NODE_FEATURE_RESOLVE, NODE_DOC_RETRIEVE)
                .addEdge(NODE_FEATURE_RESOLVE, NODE_FAQ_RETRIEVE)
                // ============ ★ 并行 fan-in: doc_retrieve / faq_retrieve → merger ============
                .addEdge(NODE_DOC_RETRIEVE, NODE_MERGER)
                .addEdge(NODE_FAQ_RETRIEVE, NODE_MERGER)
                // ============ 分流 ② merger 之后: 二选一 (B5-b-1: admin 已在 intent 阶段短路出去) ============
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
        log.info("[MainGraphConfig] mainGraph compiled: 11 nodes, 2 conditionalEdges "
                + "(routeAfterIntent=3-way: chitchat/admin/RAG; routeAfterMerger=2-way: ticket/knowledge), "
                + "1 parallel fanout (Doc + FAQ 并行检索)");
        return compiled;
    }

    // ==================== ConditionalEdge 判定 (B5-b-1 升级: 走 RouteUtil 解码层) ====================
    //
    // 第六刀 B5-b-1 关键变更:
    // 1. routeAfterIntent 从二分流升级为三分流, ADMIN_COMMAND 在 intent 后直接短路到 admin_agent,
    //    不走 RAG (不调 feature_resolve / doc_retrieve / faq_retrieve / merger).
    // 2. routeAfterMerger 简化为 ticket / knowledge 二选一; 原 isAdminMetaQuery 路径已移除.
    // 3. 所有 state 取值改走 RouteUtil.safeIntent / safeString, 修复 Graph 1.1.2 把枚举
    //    序列化为 ArrayList 导致 state.value(K, Intent.class) 静默 fallback 的潜伏 bug.

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
        // 含: 业务意图 (how_to/troubleshoot/feature_intro/default)
        //   + 非 admin 用户被分类为 admin_command 时的降级 (走正常 RAG)
        log.info("[route@intent] intent={} userRole={} → feature_resolve (RAG)",
                intent.getCode(), userRole);
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