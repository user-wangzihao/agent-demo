package com.wzh.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hello World Graph 测试端点.
 *
 * <p><b>用途</b>: 验证 spring-ai-alibaba-graph-core 1.1.2.0 在本项目里能正常依赖、
 * StateGraph 能正常编译、CompiledGraph 能正常执行.</p>
 *
 * <p><b>调用方式</b>:
 * <pre>
 *   curl -X POST http://localhost:9999/api/graph/hello \
 *        -H "Content-Type: application/json" \
 *        -d '{"message":"你好"}'
 *
 *   响应:  {"output":"Echo: 你好"}
 * </pre></p>
 *
 * <p><b>无鉴权</b>: 第一刀临时验证端点, 不接入 Token 鉴权,
 * 排除外部因素干扰. 验证完后整个 graph 包会被真实业务 Graph 取代,
 * 这个 controller 会删除.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class HelloGraphController {

    private final CompiledGraph helloGraph;

    @PostMapping("/hello")
    public Map<String, Object> hello(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        log.info("[HelloGraphController] received message='{}'", message);

        // 执行 Graph: 入参作为初始 state, 框架会自动从 __START__ 推进到 __END__
        Optional<OverAllState> result = helloGraph.invoke(
                Map.of(HelloGraphKeys.INPUT, message));

        // 从最终 state 取 output
        Map<String, Object> response = new HashMap<>();
        if (result.isPresent()) {
            response.put("output",
                    result.get().value(HelloGraphKeys.OUTPUT, String.class).orElse("(no output)"));
            response.put("rawState", result.get().data());  // 顺便返回完整 state, 方便调试观察
        } else {
            response.put("output", "(graph returned empty)");
        }
        log.info("[HelloGraphController] response={}", response);
        return response;
    }
}