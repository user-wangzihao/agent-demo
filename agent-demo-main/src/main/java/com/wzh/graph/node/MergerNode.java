package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.graph.support.RetrievalPostProcessor;
import com.wzh.service.AgentService;
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
 * Doc + FAQ 检索结果合并节点 (第四刀: 真合并版).
 *
 * <p><b>第四刀升级</b>:
 * <ul>
 *   <li>同时接收 Doc + FAQ 两路结果 (并行 fan-in 上游)</li>
 *   <li>Doc 路: 沿用原后处理 (分数过滤 / 分桶 / 整合 / 截断)</li>
 *   <li>FAQ 路: 不做分数过滤 (人工筛选的高质量内容直接全量传递)</li>
 *   <li>不做"分数融合": Doc 与 FAQ 走不同 collection 的向量距离,
 *       两套分数不可比. 采用"拼 Context 融合"策略, 让 LLM 自己理解两类知识.</li>
 *   <li>relatedImages 合并去重 (Doc + FAQ question/answer 图片)</li>
 *   <li>sources 分别构造 (chunkType 区分 "FAQ" / 文档原值, 前端可区分展示)</li>
 * </ul></p>
 *
 * <p><b>为什么不在 Node 内部融合排序</b>: 当前阶段 Doc 和 FAQ 在
 * Context 里分两段独立展示给 LLM, LLM 自然会优先采用 FAQ 答案
 * (KnowledgeAnswerNode 拼 Context 时 FAQ 在前). 不在 Java 侧做排序融合
 * 是为了避免引入"分数归一化"这种实测增益不明显的复杂度.</p>
 *
 * @author wzh
 * @since 2026-05-13 (第四刀)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MergerNode extends AbstractGraphNode {

    private static final String NODE_ID = "merger";

    private final ObjectMapper objectMapper;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        // ============ 读两路上游结果 ============
        List<SearchResult> rawDocChunks = (List<SearchResult>) state
                .value(GraphStateKeys.RETRIEVED_DOC_CHUNKS)
                .orElse(Collections.emptyList());
        List<SearchResult> rawFaqChunks = (List<SearchResult>) state
                .value(GraphStateKeys.RETRIEVED_FAQ_CHUNKS)
                .orElse(Collections.emptyList());

        // ============ Doc 路: 后处理 ============
        List<SearchResult> processedDoc = RetrievalPostProcessor.postProcess(rawDocChunks);

        // ============ FAQ 路: 不做分数过滤, 直接全量传递 ============
        // (人工筛选的高质量内容; 召回数本来就有 top-k=3 限制, 不需要再过滤)
        List<SearchResult> processedFaq = new ArrayList<>(rawFaqChunks);

        // ============ 合并 relatedImages (Doc + FAQ 图片去重保序) ============
        List<String> relatedImages = new ArrayList<>();
        for (SearchResult sr : processedDoc) {
            RetrievalPostProcessor.collectImages(sr.imageUrls, relatedImages, objectMapper);
        }
        for (SearchResult sr : processedFaq) {
            RetrievalPostProcessor.collectImages(sr.imageUrls, relatedImages, objectMapper);
        }

        // ============ 分别构造 sources, 然后合并 ============
        // chunkType="FAQ" 让前端能区分; 文档侧保留原 chunkType (text_main / image_description 等)
        List<AgentService.SourceInfo> docSources = RetrievalPostProcessor.toSourceInfoList(processedDoc);
        List<AgentService.SourceInfo> faqSources = RetrievalPostProcessor.toFaqSourceInfoList(processedFaq);
        List<AgentService.SourceInfo> sources = new ArrayList<>();
        sources.addAll(faqSources);   // FAQ 在前, 与 Context 拼接顺序对齐
        sources.addAll(docSources);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.RETRIEVED_DOC_CHUNKS, processedDoc);
        partial.put(GraphStateKeys.RETRIEVED_FAQ_CHUNKS, processedFaq);
        partial.put(GraphStateKeys.RELATED_IMAGES, relatedImages);
        partial.put(GraphStateKeys.SOURCES, sources);

        log.info("[{}] doc: raw={} → processed={} | faq: raw={} → processed={} | images={} sources={}",
                NODE_ID, rawDocChunks.size(), processedDoc.size(),
                rawFaqChunks.size(), processedFaq.size(),
                relatedImages.size(), sources.size());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] doc=" + processedDoc.size()
                        + " faq=" + processedFaq.size()
                        + " images=" + relatedImages.size()
                        + " sources=" + sources.size());
        return partial;
    }
}