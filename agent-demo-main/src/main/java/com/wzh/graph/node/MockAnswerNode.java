package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.service.MilvusService.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock 回答节点 (3.A 微调; 3.B 删除).
 *
 * <p><b>3.A 变化</b>: 把检索结果摘要也写到 mockAnswer 里, 让 Controller 看到完整流程产物.</p>
 *
 * <p><b>3.B 退场</b>: 用 KnowledgeAnswerNode / TicketAgentNode / AdminAgentNode 三个真实节点
 * + conditionalEdge 分流取代.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Component
public class MockAnswerNode extends AbstractGraphNode {

    private static final String NODE_ID = "mock_answer";

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        String enhanced = state.value(GraphStateKeys.ENHANCED_MESSAGE, String.class).orElse("");
        Object intent = state.value(GraphStateKeys.INTENT).orElse(null);
        String matchedFeature = state.value(GraphStateKeys.MATCHED_FEATURE, String.class).orElse(null);
        List<SearchResult> docChunks = (List<SearchResult>) state
                .value(GraphStateKeys.RETRIEVED_DOC_CHUNKS).orElse(Collections.emptyList());
        List<String> relatedImages = (List<String>) state
                .value(GraphStateKeys.RELATED_IMAGES).orElse(Collections.emptyList());

        StringBuilder sb = new StringBuilder();
        sb.append("[MOCK] 这是第 3.A 刀 Graph 骨架的占位回答.\n");
        sb.append("- 你的问题: ").append(enhanced).append("\n");
        sb.append("- 识别意图: ").append(intent).append("\n");
        sb.append("- 匹配 feature: ").append(matchedFeature).append("\n");
        sb.append("- 检索到文档 chunk: ").append(docChunks.size()).append(" 条\n");
        sb.append("- 关联图片: ").append(relatedImages.size()).append(" 张\n");
        if (!docChunks.isEmpty()) {
            sb.append("- 首条 chunk 预览: feature=").append(docChunks.get(0).featureName)
                    .append(" type=").append(docChunks.get(0).chunkType)
                    .append(" score=").append(String.format("%.3f", docChunks.get(0).score))
                    .append("\n");
        }
        sb.append("3.B 完成后, 这里会被真实 KnowledgeAnswerNode 取代.");

        String mockAnswer = sb.toString();
        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.FINAL_ANSWER, mockAnswer);

        log.info("[{}] generated mock answer ({} chars, docChunks={})",
                NODE_ID, mockAnswer.length(), docChunks.size());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] mock answer based on " + docChunks.size() + " chunks");
        return partial;
    }
}