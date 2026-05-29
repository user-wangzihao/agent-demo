package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.config.FaqRetrieveProperties;
import com.wzh.config.SelfRagProperties;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.ChatClientInvoker;
import com.wzh.graph.support.GraphMetricsCollector;
import com.wzh.graph.support.RetrievalPostProcessor;
import com.wzh.graph.support.RouteUtil;
import com.wzh.graph.support.SourceInfo;
import com.wzh.graph.support.SystemPromptBuilder;
import com.wzh.graph.support.TokenSinkRegistry;
import com.wzh.graph.support.TokenStreamSink;
import com.wzh.model.selfrag.SelfRagComparison;
import com.wzh.model.selfrag.SelfRagJudgement;
import com.wzh.service.DashScopeService;
import com.wzh.service.FaqMilvusService;
import com.wzh.service.MilvusService.SearchResult;
import com.wzh.service.ProductionRetrieveService;
import com.wzh.service.QueryRewriteService;
import com.wzh.service.intent.IntentBoostUtil;
import com.wzh.service.selfrag.SelfRagEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识问答节点 (Self-RAG 升级: 生成后自评 + best-of-2 择优).
 *
 * <p><b>历史</b>: 3.C 双模式 + history; 第六刀 knowledgeChatClient 工具集隔离;
 * 最后一刀 Self-RAG 自反思 (本次)。</p>
 *
 * <p><b>Self-RAG 三阶段 (节点内同步闭环, 路 Y)</b>:
 * <pre>
 *   阶段1 生成第1版 v1 (同步 NOOP, 不推流)
 *   阶段2 judge(v1) 三维诊断 → verdict
 *           ├─ PASS         → 采纳 v1 (收敛优化: 好问题不生成第2版)
 *           ├─ RETRY_GEN    → 同 context 换 prompt 生成 v2 → compare 择优
 *           └─ RETRY_RETRIEVE → rewrite + 节点内重检索得新 context → 生成 v2 → compare 择优
 *   阶段3 compare 后:
 *           ├─ winner_acceptable=true  → 采纳 winner (选 B 时同步替换 SOURCES/RELATED_IMAGES)
 *           └─ winner_acceptable=false → 假问题, 返回兜底话术 + 标记 SKIP_CACHE
 * </pre></p>
 *
 * <p><b>为什么节点内重检索 (路 Y) 而非 Graph 回边 (路 X)</b>: 回边会让 Graph 成环,
 * 需 RETRY_COUNT 防死循环, 且回边 + 跨调用 state 复用是已被坑过两次的高危区
 * (B3-c CacheCheckNode 残留 / Batch1 phaseLog 累加)。节点内闭环让本节点"自给自足",
 * Graph 拓扑零改动, 无任何状态机层面新风险。检索能力直接复用
 * {@link ProductionRetrieveService#retrieveByMatchedFeature} + {@link FaqMilvusService}
 * + {@link RetrievalPostProcessor} 静态后处理, 不重造轮子。</p>
 *
 * <p><b>与流式的关系</b>: 本节点全程同步生成 (sink=NOOP), 不推流。最终答案写 FINAL_ANSWER,
 * 由前端模拟流式回放 (Replay SSE, 前端 Batch)。Self-RAG 关闭 (enabled=false) 时退化为
 * "单版同步生成", 行为等同引入前 (除流式改同步)。</p>
 *
 * <p><b>状态残留铁律</b>: {@link GraphStateKeys#SELF_RAG_SKIP_CACHE} 被 Controller 读,
 * 故所有出口分支无条件显式 put (默认 false)。</p>
 *
 * @author wzh
 * @since 2026-05-12 (3.C) / 2026-05-28 (Self-RAG)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeAnswerNode extends AbstractGraphNode {

    private static final String NODE_ID = "knowledge_answer";

    @Qualifier("knowledgeChatClient")
    private final ChatClient knowledgeChatClient;

    private final GraphMetricsCollector metricsCollector;

    // ===== Self-RAG 依赖 (全部已有 bean, 路 Y 节点内闭环所需) =====
    private final SelfRagProperties selfRagProperties;
    private final SelfRagEvaluator selfRagEvaluator;
    private final ProductionRetrieveService productionRetrieveService;
    private final DashScopeService dashScopeService;
    private final FaqMilvusService faqMilvusService;
    private final FaqRetrieveProperties faqRetrieveProperties;
    private final QueryRewriteService queryRewriteService;
    private final ObjectMapper objectMapper;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        // ============ 1. 取 state ============
        String enhanced = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class)
                .orElse(state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse(""));
        String userRole = state.value(GraphStateKeys.USER_ROLE, String.class).orElse("user");
        Intent intent = RouteUtil.safeIntent(state);
        String matchedFeature = RouteUtil.safeString(state, GraphStateKeys.MATCHED_FEATURE, null);
        List<SearchResult> processedDoc = (List<SearchResult>) state
                .value(GraphStateKeys.RETRIEVED_DOC_CHUNKS).orElse(Collections.emptyList());
        List<SearchResult> processedFaq = (List<SearchResult>) state
                .value(GraphStateKeys.RETRIEVED_FAQ_CHUNKS).orElse(Collections.emptyList());
        List<ChatMessage> history = (List<ChatMessage>) state
                .value(GraphStateKeys.HISTORY_MESSAGES).orElse(Collections.emptyList());
        Map<String, Object> toolContext = buildToolContext(state);

        // 注意: 本节点全程同步生成, 不取流式 sink (Replay 由前端负责)。
        // 阶段1/2/3 生成一律传 NOOP, 让 ChatClientInvoker 走 .call() 同步分支。

        // ============ 2. 生成第1版 v1 (同步) ============
        String contextV1 = buildRetrievedContext(processedDoc, processedFaq);
        String v1 = generate(contextV1, userRole, intent, history, enhanced, toolContext, null);

        Map<String, Object> partial = new HashMap<>();
        // 状态残留铁律: SKIP_CACHE 先无条件置 false, 后续分支按需覆盖为 true
        partial.put(GraphStateKeys.SELF_RAG_SKIP_CACHE, false);

        // ============ Self-RAG 关闭 → 退化为单版同步生成 ============
        if (!selfRagProperties.isEnabled()) {
            partial.put(GraphStateKeys.FINAL_ANSWER, v1);
            partial.put(GraphStateKeys.SELF_RAG_VERDICT, "DISABLED");
            metricsCollector.recordReflectVerdict("DISABLED");
            log.info("[{}] self-rag disabled, 单版同步生成 answerLen={}",
                    NODE_ID, v1 == null ? 0 : v1.length());
            appendPhaseLog(state, partial,
                    "[" + NODE_ID + "] self-rag=off answer=" + (v1 == null ? 0 : v1.length()) + "ch");
            return partial;
        }

        // ============ 3. judge 第1版 ============
        SelfRagJudgement judgement = selfRagEvaluator.judge(enhanced, contextV1, v1, intent);

        // ---- PASS: 收敛优化, 直接采纳 v1, 不生成第2版 ----
        if (judgement.getVerdict() == SelfRagJudgement.Verdict.PASS) {
            partial.put(GraphStateKeys.FINAL_ANSWER, v1);
            partial.put(GraphStateKeys.SELF_RAG_VERDICT, "PASS");
            metricsCollector.recordReflectVerdict("PASS");
            log.info("[{}] judge PASS, 采纳第1版 (跳过第2版) answerLen={}",
                    NODE_ID, v1 == null ? 0 : v1.length());
            appendPhaseLog(state, partial,
                    "[" + NODE_ID + "] verdict=PASS answer=" + (v1 == null ? 0 : v1.length()) + "ch");
            return partial;
        }

        // ============ 4. 生成第2版 v2 (按 verdict 决定是否重检索) ============
        String v2;
        String contextV2;
        boolean retrieved = false;          // 第2版是否重新检索过 (决定 compare 用哪份 context)
        // 重检索产出的新结果 (仅 RETRY_RETRIEVE 且最终选 B 时, 用于替换 SOURCES/RELATED_IMAGES)
        List<SearchResult> reDoc = null;
        List<SearchResult> reFaq = null;

        if (judgement.getVerdict() == SelfRagJudgement.Verdict.RETRY_RETRIEVE) {
            // grounded=false: context 不对路 → rewrite + 节点内重检索
            String rewritten = rewriteForRetrieve(enhanced);
            reDoc = reRetrieveDoc(rewritten, matchedFeature, intent);
            reFaq = reRetrieveFaq(rewritten, matchedFeature);
            contextV2 = buildRetrievedContext(reDoc, reFaq);
            retrieved = true;
            log.info("[{}] RETRY_RETRIEVE rewritten='{}' reDoc={} reFaq={}",
                    NODE_ID, rewritten, reDoc.size(), reFaq.size());
            // 用新 context + 重生成提示生成第2版
            v2 = generate(contextV2, userRole, intent, history, enhanced, toolContext,
                    buildRetryHint(judgement));
        } else {
            // RETRY_GEN: 召回是对的, 生成没用好 → 同 context 换 prompt 重生成
            contextV2 = contextV1;
            v2 = generate(contextV1, userRole, intent, history, enhanced, toolContext,
                    buildRetryHint(judgement));
        }

        // ============ 5. compare 择优 ============
        // compare 的 context 用第2版的 (重检索后 context 可能已变), 让 judge 基于 B 实际依据的资料判断
        SelfRagComparison comparison = selfRagEvaluator.compare(enhanced, contextV2, v1, v2);

        // ---- 假问题: 赢的那版也不合格 → 兜底话术, 不写缓存 ----
        if (!comparison.isWinnerAcceptable()) {
            String fallback = selfRagProperties.getGiveUpFallback();
            partial.put(GraphStateKeys.FINAL_ANSWER, fallback);
            partial.put(GraphStateKeys.SELF_RAG_SKIP_CACHE, true);   // 兜底话术绝不写缓存
            partial.put(GraphStateKeys.SELF_RAG_VERDICT, "GIVE_UP");
            metricsCollector.recordReflectVerdict("GIVE_UP");
            log.info("[{}] compare winner_acceptable=false (假问题), 返回兜底话术, 跳过缓存. reason={}",
                    NODE_ID, comparison.getReason());
            appendPhaseLog(state, partial,
                    "[" + NODE_ID + "] verdict=GIVE_UP fallback skip-cache");
            return partial;
        }

        // ---- 正常择优 ----
        boolean pickB = comparison.picksSecondVersion();
        String winner = pickB ? v2 : v1;
        partial.put(GraphStateKeys.FINAL_ANSWER, winner);

        // 选 B 且 B 是重检索版 → 替换 SOURCES / RELATED_IMAGES, 否则前端来源/图片与答案对不上
        if (pickB && retrieved) {
            replaceSourcesAndImages(partial, reDoc, reFaq);
        }

        String verdictTag = judgement.getVerdict().name() + (pickB ? "_WIN_B" : "_WIN_A");
        partial.put(GraphStateKeys.SELF_RAG_VERDICT, verdictTag);
        metricsCollector.recordReflectVerdict(verdictTag);

        log.info("[{}] verdict={} winner={} retrieved={} answerLen={}",
                NODE_ID, verdictTag, pickB ? "B(v2)" : "A(v1)", retrieved,
                winner == null ? 0 : winner.length());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] verdict=" + verdictTag
                        + " winner=" + (pickB ? "B" : "A")
                        + " answer=" + (winner == null ? 0 : winner.length()) + "ch");
        return partial;
    }

    // =========================================================================
    // 生成
    // =========================================================================

    /**
     * 同步生成一版答案 (sink=NOOP)。
     *
     * @param retryHint 第2版的重生成提示 (附加到 SystemPrompt 末尾, 引导避开第1版的问题);
     *                  第1版传 null
     */
    private String generate(String retrievedContext, String userRole, Intent intent,
                            List<ChatMessage> history, String enhanced,
                            Map<String, Object> toolContext, String retryHint) {
        String systemPrompt = SystemPromptBuilder.buildSystemPrompt(retrievedContext, userRole, intent);
        if (retryHint != null && !retryHint.isBlank()) {
            systemPrompt = systemPrompt + "\n\n" + retryHint;
        }
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        appendHistoryMessages(messages, history);
        messages.add(new UserMessage(enhanced));

        // 全程同步: 传 NOOP → ChatClientInvoker 走 .call()
        return ChatClientInvoker.invoke(knowledgeChatClient, new Prompt(messages),
                toolContext, TokenStreamSink.NOOP, intent, metricsCollector);
    }

    /**
     * 第2版的重生成提示, 把第1版的诊断问题告诉 LLM, 引导其改进。
     */
    private String buildRetryHint(SelfRagJudgement j) {
        StringBuilder sb = new StringBuilder("【重要】上一版回答存在以下问题, 请针对性改进后重新作答:");
        if (!j.isGrounded()) {
            sb.append("\n- 上一版偏离了检索资料/存在编造, 请严格基于上方资料作答, 资料没有的不要杜撰;");
        }
        if (!j.isRelevant()) {
            sb.append("\n- 上一版没有答到用户真正问的点, 请紧扣问题核心作答;");
        }
        if (!j.isComplete()) {
            sb.append("\n- 上一版回答不够完整 (操作类应给完整步骤、排查类应给完整解法、介绍类应讲清是什么), 请补全;");
        }
        return sb.toString();
    }

    // =========================================================================
    // 节点内重检索 (路 Y) — 复用现有检索能力 + 后处理静态工具
    // =========================================================================

    /**
     * RETRY_RETRIEVE 的 query 改写: 取 rewrite 的第一条变体 (与 fallback 链同源);
     * 改写失败/为空则回退原 query。
     */
    private String rewriteForRetrieve(String enhanced) {
        try {
            List<String> rewrites = queryRewriteService.rewrite(enhanced);
            if (rewrites != null && !rewrites.isEmpty()) {
                String first = rewrites.get(0);
                if (first != null && !first.isBlank()) {
                    return first.trim();
                }
            }
        } catch (Exception e) {
            log.warn("[{}] rewrite 失败, 回退原 query. err={}", NODE_ID, e.getMessage());
        }
        return enhanced;
    }

    /**
     * 重检索文档: 复用 ProductionRetrieveService + IntentBoost + RetrievalPostProcessor,
     * 与 DocRetrieveNode + MergerNode(Doc路) 行为一致。
     */
    private List<SearchResult> reRetrieveDoc(String query, String matchedFeature, Intent intent) {
        try {
            List<SearchResult> raw = productionRetrieveService
                    .retrieveByMatchedFeature(query, matchedFeature);
            IntentBoostUtil.applyBoost(raw, intent);
            return RetrievalPostProcessor.postProcess(raw);   // 分数过滤/分桶/整合, 同 MergerNode Doc 路
        } catch (Exception e) {
            log.error("[{}] 重检索 Doc 异常, 降级空 List. err={}", NODE_ID, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 重检索 FAQ: 复用 FaqRetrieveNode 的检索逻辑 (embedding → searchFaq), FAQ 路不做分数过滤。
     */
    private List<SearchResult> reRetrieveFaq(String query, String matchedFeature) {
        if (!faqRetrieveProperties.isEnabled()) {
            return Collections.emptyList();
        }
        try {
            List<Float> vector = dashScopeService.getEmbedding(query);
            if (vector == null || vector.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> features = buildFaqFeatureFilter(matchedFeature);
            List<SearchResult> results = faqMilvusService.searchFaq(
                    vector, features, faqRetrieveProperties.getTopK());
            return results == null ? Collections.emptyList() : results;
        } catch (Exception e) {
            log.error("[{}] 重检索 FAQ 异常, 降级空 List. err={}", NODE_ID, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 同 FaqRetrieveNode.buildFeatureFilter */
    private List<String> buildFaqFeatureFilter(String matchedFeature) {
        String general = faqRetrieveProperties.getGeneralMarker();
        List<String> features = new ArrayList<>();
        if (matchedFeature != null && !matchedFeature.trim().isEmpty()) {
            features.add(matchedFeature);
        }
        features.add(general);
        return features;
    }

    /**
     * 选 B(重检索版) 时, 把 SOURCES / RELATED_IMAGES 替换为重检索结果,
     * 复用 MergerNode 同款静态构造逻辑, 保证前端展示的来源/图片与最终答案一致。
     */
    private void replaceSourcesAndImages(Map<String, Object> partial,
                                         List<SearchResult> reDoc,
                                         List<SearchResult> reFaq) {
        List<SearchResult> docs = reDoc == null ? Collections.emptyList() : reDoc;
        List<SearchResult> faqs = reFaq == null ? Collections.emptyList() : reFaq;

        // relatedImages: Doc + FAQ 去重保序
        List<String> relatedImages = new ArrayList<>();
        for (SearchResult sr : docs) {
            RetrievalPostProcessor.collectImages(sr.imageUrls, relatedImages, objectMapper);
        }
        for (SearchResult sr : faqs) {
            RetrievalPostProcessor.collectImages(sr.imageUrls, relatedImages, objectMapper);
        }

        // sources: FAQ 在前, 与 Context 拼接顺序对齐 (同 MergerNode)
        List<SourceInfo> sources = new ArrayList<>();
        sources.addAll(RetrievalPostProcessor.toFaqSourceInfoList(faqs));
        sources.addAll(RetrievalPostProcessor.toSourceInfoList(docs));

        partial.put(GraphStateKeys.RELATED_IMAGES, relatedImages);
        partial.put(GraphStateKeys.SOURCES, sources);
        log.info("[{}] 选B(重检索版), 替换 sources={} images={}",
                NODE_ID, sources.size(), relatedImages.size());
    }

    // =========================================================================
    // 以下为原有逻辑, 保持不变
    // =========================================================================

    /**
     * 拼检索到的 chunks 为 system prompt 的 context 段 (第四刀升级: 加 FAQ 段).
     *
     * <p>FAQ 在前, 文档在后; image_description chunk 不进 Context (避免噪声)。</p>
     */
    private String buildRetrievedContext(List<SearchResult> processedDoc,
                                         List<SearchResult> processedFaq) {
        if ((processedDoc == null || processedDoc.isEmpty())
                && (processedFaq == null || processedFaq.isEmpty())) {
            return "";
        }
        StringBuilder context = new StringBuilder();

        if (processedFaq != null && !processedFaq.isEmpty()) {
            context.append("以下是相关的常见问答 (FAQ, 优先参考):\n\n");
            int idx = 0;
            for (SearchResult sr : processedFaq) {
                if ("image_description".equals(sr.chunkType)) continue;
                idx++;
                context.append(String.format("【FAQ %d】(相关度: %.2f)%n%s%n%n",
                        idx, sr.score, sr.content));
            }
        }

        if (processedDoc != null && !processedDoc.isEmpty()) {
            context.append("以下是从知识库文档中检索到的相关信息:\n\n");
            int idx = 0;
            for (SearchResult sr : processedDoc) {
                if ("image_description".equals(sr.chunkType)) continue;
                idx++;
                context.append(String.format("【知识片段 %d】(来源: %s - %s, 相关度: %.2f)%n%s%n%n",
                        idx, sr.featureName, sr.chunkType, sr.score, sr.content));
            }
        }

        return context.toString();
    }

    private void appendHistoryMessages(
            List<org.springframework.ai.chat.messages.Message> target,
            List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return;
        for (ChatMessage m : history) {
            if ("user".equals(m.getRole())) {
                target.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                target.add(new AssistantMessage(m.getContent()));
            }
        }
    }

    private Map<String, Object> buildToolContext(OverAllState state) {
        Object userIdObj = state.value(GraphStateKeys.USER_ID).orElse(null);
        Object userNameObj = state.value(GraphStateKeys.USER_NAME).orElse(null);
        Object sessionIdObj = state.value(GraphStateKeys.SESSION_ID).orElse(null);
        return Map.of(
                "userId", userIdObj == null ? "unknown" : String.valueOf(userIdObj),
                "userName", userNameObj == null ? "未知用户" : String.valueOf(userNameObj),
                "sessionId", sessionIdObj == null ? 0L : toLong(sessionIdObj)
        );
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return 0L; }
    }
}