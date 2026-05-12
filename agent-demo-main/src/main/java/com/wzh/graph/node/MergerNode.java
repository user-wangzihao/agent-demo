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
 * Doc + FAQ 检索结果合并节点 (3.A 升级版).
 *
 * <p><b>3.A 职责</b>: 单源 (仅 Doc) 时, 对 Doc 检索结果做后处理 (分数过滤/分桶/整合/截断),
 * 同时提取 relatedImages 和 sources 元数据. FAQ 路径在 3.A 仍为空 (第四刀真接入).</p>
 *
 * <p><b>为什么后处理在这里, 不在 DocRetrieveNode</b>:
 * 后处理本质是"为生成答案准备数据" (限制条数 / 图片单独抽取 / 来源元数据).
 * 第四刀 FAQ 加入后, FAQ chunks 也会进 MergerNode. 所以 MergerNode 是
 * "准备生成上下文"的统一入口, 后处理放这里语义上最对.</p>
 *
 * <p><b>第四刀升级</b>:
 * <ol>
 *   <li>不再单源透传, 接收 Doc + FAQ 两路结果</li>
 *   <li>不做分数融合 (走拼 Context 融合策略, 让 LLM 自己理解两类知识)</li>
 *   <li>分别记录 docRetrieveMs / faqRetrieveMs 到 phaseLatencies</li>
 * </ol></p>
 *
 * @author wzh
 * @since 2026-05-11
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
        List<SearchResult> rawDocChunks = (List<SearchResult>) state
                .value(GraphStateKeys.RETRIEVED_DOC_CHUNKS)
                .orElse(Collections.emptyList());

        // 后处理: 分数过滤 + 分桶 + 整合 + 截断
        List<SearchResult> processedDoc = RetrievalPostProcessor.postProcess(rawDocChunks);

        // 提取 relatedImages (所有 chunk 的 imageUrls 聚合, 去重保序)
        List<String> relatedImages = new ArrayList<>();
        for (SearchResult sr : processedDoc) {
            RetrievalPostProcessor.collectImages(sr.imageUrls, relatedImages, objectMapper);
        }

        // 提取 sources (过滤掉 image_description 类型)
        List<AgentService.SourceInfo> sources = RetrievalPostProcessor.toSourceInfoList(processedDoc);

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.RETRIEVED_DOC_CHUNKS, processedDoc);  // 覆盖为后处理结果
        partial.put(GraphStateKeys.RETRIEVED_FAQ_CHUNKS, Collections.emptyList());  // 3.A: FAQ 占位
        partial.put(GraphStateKeys.RELATED_IMAGES, relatedImages);
        partial.put(GraphStateKeys.SOURCES, sources);

        log.info("[{}] doc: raw={} → processed={} | images={} sources={}",
                NODE_ID, rawDocChunks.size(), processedDoc.size(),
                relatedImages.size(), sources.size());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] doc=" + processedDoc.size()
                        + " faq=0 images=" + relatedImages.size()
                        + " sources=" + sources.size());
        return partial;
    }
}