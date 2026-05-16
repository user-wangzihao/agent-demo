package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.model.intent.IntentClassificationResult;
import com.wzh.service.intent.IntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图识别节点.
 *
 * <p><b>职责</b>: 复用现有 IntentClassifier, 把 query 分类为 chitchat / how_to / troubleshoot
 * / feature_intro / default 之一, 写入 state.</p>
 *
 * <p><b>第二刀</b>: 仅识别 + 写入, 不做短路 (即 chitchat 也会继续往下走, 不在 Graph 层短路).</p>
 *
 * <p><b>第三刀</b>: 在 preprocess → intent 之间不变, 但在 intent → 后续路由 间加 conditionalEdge:
 * 若 intent.isShortCircuit() == true → 直接到 chitchat answer 节点 → END.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentNode extends AbstractGraphNode {

    private static final String NODE_ID = "intent";

    private final IntentClassifier intentClassifier;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state) {
        String userMessage = state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse("");
        // 意图识别用原始 query, 不用 enhancedMessage - 图片描述可能干扰意图判断
        // (这点和 AgentService 行为一致: 意图识别在 Step 0, 图片理解在 Step 1)
        IntentClassificationResult result = intentClassifier.classify(userMessage);

        Intent intent = result.getIntent();
        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.INTENT, intent);
        partial.put(GraphStateKeys.INTENT_SOURCE, result.getSource());
        partial.put(GraphStateKeys.INTENT_CONFIDENCE, result.getConfidence());

        // 第六刀 Batch 2 hotfix v5: 节点端写 holder 的方案已废弃.
        // 实测 Spring AI Alibaba Graph 1.1.2 对 state 里的可变对象做了拷贝,
        // 节点拿到的 holder 是副本, put 进去对 Controller 持有的原 holder 无影响.
        // Controller 改用 doOnNext 直接抢救式读取 + 反序列化 ArrayList 包装的 Intent.

        log.info("[{}] query='{}' intent={} source={} conf={}",
                NODE_ID, userMessage, intent.getCode(), result.getSource(), result.getConfidence());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] " + intent.getCode() + " (" + result.getSource()
                        + ", conf=" + result.getConfidence() + ")");
        return partial;
    }
}