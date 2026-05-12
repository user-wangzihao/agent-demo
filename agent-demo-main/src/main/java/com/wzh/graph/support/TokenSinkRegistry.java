package com.wzh.graph.support;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TokenStreamSink 全局注册表 (3.C hotfix 引入).
 *
 * <p><b>为什么需要</b>: Spring AI Alibaba Graph 框架内部会对 state 做 clone (序列化),
 * 直接把 sink (lambda) 塞 state 会导致 Jackson 沿着 lambda 捕获的 SseEmitter 反射钻到 Tomcat
 * 内部, 抛 NumberFormatException. 所以 sink 不能进 state, 只能进单独的 Map.</p>
 *
 * <p><b>绑定 key</b>: 使用 Graph 的 {@code _graph_execution_id_} 作为 key.
 * 这是 Graph 框架本身为每次执行分配的 UUID, 我们在 initial state 里预先塞一个,
 * 框架会沿用而不是重新生成. Node 内部通过 state.value("_graph_execution_id_")
 * 拿到 id, 再来这里取 sink.</p>
 *
 * <p><b>生命周期</b>: bind 在 Controller 启动 Graph 前, unbind 在 done / error / finally.
 * 永远配对调用, 防止 SseEmitter 泄漏.</p>
 *
 * @author wzh
 * @since 2026-05-12 (3.C hotfix)
 */
@Slf4j
public final class TokenSinkRegistry {

    private static final ConcurrentMap<String, TokenStreamSink> REGISTRY = new ConcurrentHashMap<>();

    /** Graph 框架内部用的执行 id state key, 与 CompiledGraph.stateCreate 保持一致. */
    public static final String EXECUTION_ID_KEY = "_graph_execution_id_";

    private TokenSinkRegistry() {}

    public static void bind(String execId, TokenStreamSink sink) {
        if (execId == null || sink == null) return;
        REGISTRY.put(execId, sink);
        log.debug("[TokenSinkRegistry] bind execId={} (size after={})", execId, REGISTRY.size());
    }

    /** 永远返回非 null, 没找到返回 NOOP. */
    public static TokenStreamSink get(String execId) {
        if (execId == null) return TokenStreamSink.NOOP;
        return REGISTRY.getOrDefault(execId, TokenStreamSink.NOOP);
    }

    public static void unbind(String execId) {
        if (execId == null) return;
        REGISTRY.remove(execId);
        log.debug("[TokenSinkRegistry] unbind execId={} (size after={})", execId, REGISTRY.size());
    }
}