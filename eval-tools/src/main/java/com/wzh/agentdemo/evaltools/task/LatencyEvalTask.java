package com.wzh.agentdemo.evaltools.task;

import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 端到端延迟评估任务 (P50 / P95).
 *
 * <p><b>Batch 1 状态</b>: 空骨架, {@link #run()} 直接返回 SKIPPED.</p>
 * <p><b>Batch 6 计划</b>: HTTP 调主应用 {@code /api/graph/chat-stream} SSE 端点,
 * 跑 N 次记录端到端耗时, 计算 P50 / P95. 用 eval-set.txt 既有 24 条作为流量样本.</p>
 *
 * @author wzh
 * @since 2026-05-19 (Batch 1 骨架)
 */
@Slf4j
public class LatencyEvalTask implements EvalTask {

    public static final String TASK_NAME = "latency";
    public static final String DISPLAY_NAME = "端到端延迟 (P50 / P95)";

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public EvalTaskResult run() {
        log.info("[{}] 暂未实现, 跳过 (Batch 6 将填充)", TASK_NAME);
        return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                "Batch 1 阶段: 评估框架骨架已就绪, 业务逻辑将在 Batch 6 填充.");
    }
}
