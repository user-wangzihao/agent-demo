package com.wzh.graph.support;

/**
 * Token 流推送接收器抽象 (3.C 引入).
 *
 * <p><b>职责</b>: 让 Answer Node 在不直接持有 SseEmitter 的前提下推送增量 token,
 * 保持 Node 对传输层无感.</p>
 *
 * <p><b>双模式策略</b>:
 * <ul>
 *   <li>同步端点 (/api/graph/chat) 不传 sink → state 取出 {@link #NOOP} → Node 走 .call()</li>
 *   <li>SSE 端点 (/api/graph/chat-stream) 传桥接 emitter 的 sink → Node 走 .stream()</li>
 * </ul></p>
 *
 * <p><b>第六刀升级方向</b>: 当做法 Y (真 Multi-Agent + 独立 ChatClient bean) 引入后,
 * 这个抽象仍然适用, sink 实现可能扩展为支持 multi-agent 并行推流时的合流.</p>
 *
 * @author wzh
 * @since 2026-05-12
 */
@FunctionalInterface
public interface TokenStreamSink {

    /** 推送一个增量 token. */
    void onToken(String delta);

    /** 空实现, 用于同步 invoke 场景. Node 检测到 NOOP 时降级为 .call(). */
    TokenStreamSink NOOP = delta -> {};
}