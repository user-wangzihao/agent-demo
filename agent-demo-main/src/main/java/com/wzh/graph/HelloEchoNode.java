package com.wzh.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Hello World Graph 的唯一业务节点.
 *
 * <p><b>职责</b>: 从 state 里读 input, 加个 "Echo: " 前缀, 写回 output.</p>
 *
 * <p><b>实现说明</b>:
 * <ul>
 *   <li>实现 NodeAction (同步形态), 在 Config 里用 node_async() 包装成 AsyncNodeAction</li>
 *   <li>返回 Map 是 "partial state": 框架会按 KeyStrategy 自动合并进 OverAllState</li>
 *   <li>这里只写 output 一个 key, 不影响 input, 也不影响其他 key</li>
 * </ul></p>
 *
 * <p><b>注意</b>: 这是第一刀的临时节点, 后续真实业务节点 (PreprocessNode / IntentNode /
 * RagRetrieveNode 等) 跑通后会删除.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
public class HelloEchoNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 从 state 取用户输入; 取不到给个默认值, 避免 NPE
        String input = state.value(HelloGraphKeys.INPUT, String.class).orElse("(empty)");

        String output = "Echo: " + input;
        log.info("[HelloEchoNode] input='{}' → output='{}'", input, output);

        // partial state: 只更新 output, input 不动
        return Map.of(HelloGraphKeys.OUTPUT, output);
    }
}