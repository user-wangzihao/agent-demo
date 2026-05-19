package com.wzh.agentdemo.evaltools.task;

import com.wzh.agentdemo.evaltools.model.EvalTaskResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 检索质量评估任务 (MRR@5 / NDCG@5).
 *
 * <p><b>Batch 1 状态</b>: 空骨架, {@link #run()} 直接返回 SKIPPED.</p>
 * <p><b>Batch 5 计划</b>: 复用既有 {@code MilvusBulkReader.vectorSearch}, 直连 Milvus 跑
 * 检索. 因为文档可能被重新学习导致 chunk_id 漂移, 采用 <b>content 软匹配</b> 策略
 * (召回 chunk 的 content 与 EvalCase.answer 关键词命中) 评估, 而非 chunk_id 严格匹配.</p>
 *
 * @author wzh
 * @since 2026-05-19 (Batch 1 骨架)
 */
@Slf4j
public class RetrievalEvalTask implements EvalTask {

    public static final String TASK_NAME = "retrieval";
    public static final String DISPLAY_NAME = "检索质量 (MRR@5 / NDCG@5)";

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
        log.info("[{}] 暂未实现, 跳过 (Batch 5 将填充)", TASK_NAME);
        return EvalTaskResult.skipped(TASK_NAME, DISPLAY_NAME,
                "Batch 1 阶段: 评估框架骨架已就绪, 业务逻辑将在 Batch 5 填充.");
    }
}
