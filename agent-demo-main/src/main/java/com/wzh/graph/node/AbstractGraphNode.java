package com.wzh.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.wzh.graph.core.GraphStateKeys;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Graph 节点抽象基类.
 *
 * <p><b>解决的问题</b>: 每个 Node 都要做三件事 — 记耗时, 追加 phaseLog, 异常兜底.
 * 如果每个 Node 都写一遍, 代码重复且容易遗漏. 抽到基类里, 子类只关心业务逻辑.</p>
 *
 * <p><b>使用方式</b>: 子类不重写 apply(), 改写 doApply() 即可.</p>
 *
 * <p><b>append 实现</b>: OverAllState 用 ReplaceStrategy 注册的 key, 写入就覆盖.
 * 想要累加 (phaseLog / phaseLatencies), 需要 "读旧值 → append → 写新完整值" 三步.
 * mergeAppendList() 和 mergeAppendMap() 封装了这个模式.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
public abstract class AbstractGraphNode implements NodeAction {

    /** 子类节点 id (用于日志和 phaseLog) */
    protected abstract String nodeId();

    /**
     * 子类实现实际业务逻辑.
     *
     * @param state 当前 Graph 状态
     * @return 本节点要更新到 state 的 partial map (不要包含 phaseLatencies / phaseLog,
     *         基类会自动追加)
     */
    protected abstract Map<String, Object> doApply(OverAllState state) throws Exception;

    @Override
    public final Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        String nid = nodeId();
        Map<String, Object> partial;

        try {
            partial = doApply(state);
            if (partial == null) {
                partial = new HashMap<>();
            } else {
                partial = new HashMap<>(partial);  // 防御性拷贝, 避免 Map.of() 不可变带来麻烦
            }
        } catch (Exception e) {
            log.error("[{}] 节点执行异常", nid, e);
            partial = new HashMap<>();
            // 把错误信息写进 phaseLog, 不抛出 - 让 Graph 继续走完, 下游节点自行降级
            appendPhaseLog(state, partial, "[" + nid + "] ERROR: " + e.getMessage());
        }

        long cost = System.currentTimeMillis() - start;
        appendPhaseLatency(state, partial, nid, cost);
        // 如果业务逻辑没有显式 log, 至少留个 "节点执行完毕" 的痕迹
        if (!partial.containsKey(GraphStateKeys.PHASE_LOG)) {
            appendPhaseLog(state, partial, "[" + nid + "] done (" + cost + "ms)");
        }
        return partial;
    }

    // ==================== 给子类调用的工具方法 ====================

    /**
     * 在 partial state 里追加一行 phaseLog.
     *
     * <p>read-modify-write: 先读 state 已有的 phaseLog (可能为空),
     * 添加新行, 写回 partial. 多次调用会累加.</p>
     */
    @SuppressWarnings("unchecked")
    protected void appendPhaseLog(OverAllState state, Map<String, Object> partial, String line) {
        List<String> log = (List<String>) partial.get(GraphStateKeys.PHASE_LOG);
        if (log == null) {
            log = new ArrayList<>();   // 只放本节点新增, AppendStrategy 会自动 concat 历史
        }
        log.add(line);
        partial.put(GraphStateKeys.PHASE_LOG, log);
    }

    /**
     * 在 partial state 里追加一条 phaseLatencies.
     *
     * <p>read-modify-write: 先读 state 已有的 Map (可能为空), put 新条目, 写回 partial.</p>
     */
    @SuppressWarnings("unchecked")
    protected void appendPhaseLatency(OverAllState state, Map<String, Object> partial,
                                      String nodeName, long costMs) {
        Map<String, Long> map = (Map<String, Long>) partial.get(GraphStateKeys.PHASE_LATENCIES);
        if (map == null) {
            map = new HashMap<>();   // 只放本节点新增, MergeStrategy 会自动 putAll 历史
        }
        map.put(nodeName, costMs);
        partial.put(GraphStateKeys.PHASE_LATENCIES, map);
    }
}