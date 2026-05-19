package com.wzh.agentdemo.evaltools.task;

import com.wzh.agentdemo.evaltools.model.EvalTaskResult;

/**
 * 评估任务统一接口 (Batch 1 引入).
 *
 * <p>每个评估指标 (意图准确率 / 路由正确率 / 检索 MRR / 端到端延迟) 对应一个实现.
 * {@code EvalRunner} 按 {@link #name()} 调度.</p>
 *
 * <p><b>实现约定</b>:
 * <ul>
 *   <li>{@link #run()} 必须不抛异常. 内部 catch 异常并返回
 *       {@link EvalTaskResult#error(String, String, String)}.</li>
 *   <li>数据源缺失 / 依赖不可用时返回
 *       {@link EvalTaskResult#skipped(String, String, String)}, 让一键全跑能继续.</li>
 *   <li>任务自己负责日志输出, EvalRunner 只做编排.</li>
 * </ul>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 1)
 */
public interface EvalTask {

    /**
     * 任务标识. 与 {@code --task=xxx} CLI 参数对齐.
     * <p>建议取值: intent / route / retrieval / latency.</p>
     */
    String name();

    /**
     * 任务展示名 (中文). 报告 markdown 标题用.
     */
    String displayName();

    /**
     * 执行任务. 永不抛异常.
     */
    EvalTaskResult run();
}
