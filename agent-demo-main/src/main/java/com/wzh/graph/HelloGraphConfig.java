package com.wzh.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Hello World Graph 的装配配置.
 *
 * <p><b>Graph 形状</b>:
 * <pre>
 *     __START__  →  echo  →  __END__
 * </pre></p>
 *
 * <p><b>关键设计点</b>:
 * <ol>
 *   <li>KeyStrategyFactory: 声明 State 里所有合法 key 和合并策略.
 *       这里 input 和 output 都用 ReplaceStrategy (覆盖式, 节点写就覆盖, 不累加).
 *       如果用 AppendStrategy 则会累加成 List - 适合"对话历史"这种场景, 但这里不需要.</li>
 *   <li>node_async(...): 把同步的 NodeAction 包装成框架要求的 AsyncNodeAction.</li>
 *   <li>compile(): 校验图结构 + 生成可执行 CompiledGraph (默认带 MemorySaver checkpoint).</li>
 *   <li>GraphStateException: addNode/addEdge 都可能抛, 在 @Bean 方法里 throws 即可,
 *       Spring 启动时如果图结构有问题会直接启动失败 - 这是好事, fail fast.</li>
 * </ol></p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Configuration
public class HelloGraphConfig {

    /** Graph 内部节点 id (不是 Spring Bean 名, 只在图内部用) */
    private static final String NODE_ECHO = "echo";

    /**
     * 声明 State 中所有 key 及其合并策略.
     *
     * <p>OverAllState 写入时必须先注册 key + 策略, 否则 updateState() 会忽略写入.</p>
     */
    @Bean
    public KeyStrategyFactory helloGraphKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(HelloGraphKeys.INPUT, new ReplaceStrategy());
            strategies.put(HelloGraphKeys.OUTPUT, new ReplaceStrategy());
            return strategies;
        };
    }

    /**
     * 装配 StateGraph 并编译为 CompiledGraph.
     *
     * <p>Bean 名 helloGraph - 后续真实业务 Graph 起名 mainGraph, 用 Bean 名区分.</p>
     */
    @Bean
    public CompiledGraph helloGraph(KeyStrategyFactory helloGraphKeyStrategyFactory)
            throws GraphStateException {
        StateGraph graph = new StateGraph("helloGraph", helloGraphKeyStrategyFactory)
                .addNode(NODE_ECHO, node_async(new HelloEchoNode()))
                .addEdge(StateGraph.START, NODE_ECHO)
                .addEdge(NODE_ECHO, StateGraph.END);

        CompiledGraph compiled = graph.compile();
        log.info("[HelloGraphConfig] helloGraph compiled, nodes=1 edges=2");
        return compiled;
    }
}