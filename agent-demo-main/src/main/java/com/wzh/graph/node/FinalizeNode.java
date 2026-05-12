package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.graph.core.GraphStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收尾节点.
 *
 * <p><b>职责</b>: 不写任何业务字段, 只输出一行总结日志, 便于在控制台一眼看清整条 Graph 的执行情况.</p>
 *
 * <p><b>第三刀升级</b>: 在这里把 assistant message 写入 chat_message 表 (对齐
 * AgentService.saveAssistantMessageAndComplete()), 并触发 SSE done 事件.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Component
public class FinalizeNode extends AbstractGraphNode {

    private static final String NODE_ID = "finalize";

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        Map<String, Long> latencies = state.<Map<String, Long>>value(GraphStateKeys.PHASE_LATENCIES)
                .orElse(new HashMap<>());
        List<String> log = state.<List<String>>value(GraphStateKeys.PHASE_LOG)
                .orElse(List.of());
        String finalAnswer = state.value(GraphStateKeys.FINAL_ANSWER, String.class).orElse("(empty)");

        long total = latencies.values().stream().mapToLong(Long::longValue).sum();
        FinalizeNode.log.info("[{}] ==== Graph 执行完毕 ====", NODE_ID);
        FinalizeNode.log.info("[{}] 总耗时: {}ms; 各节点: {}", NODE_ID, total, latencies);
        FinalizeNode.log.info("[{}] 处理流程:", NODE_ID);
        for (String line : log) {
            FinalizeNode.log.info("[{}]   - {}", NODE_ID, line);
        }
        FinalizeNode.log.info("[{}] 最终回答 ({} chars): {}", NODE_ID,
                finalAnswer.length(),
                finalAnswer.length() > 100 ? finalAnswer.substring(0, 100) + "..." : finalAnswer);

        // 不写任何 state, 仅日志
        Map<String, Object> partial = new HashMap<>();
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] graph 执行完毕, 总耗时 " + total + "ms");
        return partial;
    }
}