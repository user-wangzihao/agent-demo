package com.wzh.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.graph.core.GraphStateKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 主对话 Graph 测试端点 (第二刀).
 *
 * <p><b>第二刀</b>: 同步 invoke, 返回完整 state JSON, 不接 SSE.</p>
 *
 * <p><b>第三刀升级</b>: 改为 SSE 流式输出, 对接前端 EventSource, 协议对齐
 * AgentService.chatStream() 的 meta / token / done / error 四类事件.</p>
 *
 * <p><b>调用方式</b>:
 * <pre>
 *   curl -X POST http://localhost:9999/api/graph/chat \
 *        -H "Content-Type: application/json" \
 *        -d '{
 *          "userMessage": "建模档出图标准怎么设置",
 *          "userRole": "user",
 *          "userId": 1,
 *          "userName": "test"
 *        }'
 * </pre></p>
 *
 * <p><b>无鉴权</b>: 同 HelloGraphController, 第二刀仍是验证骨架, 跳过鉴权排除干扰.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class MainGraphController {

    private final CompiledGraph mainGraph;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        log.info("[MainGraphController] received body={}", body);

        // 构造初始 state - 只放外部输入类的 key, 其它由各节点写入
        Map<String, Object> initial = new HashMap<>();
        putIfPresent(initial, GraphStateKeys.USER_MESSAGE, body.get("userMessage"));
        putIfPresent(initial, GraphStateKeys.USER_ID, body.get("userId"));
        putIfPresent(initial, GraphStateKeys.USER_NAME, body.get("userName"));
        putIfPresent(initial, GraphStateKeys.USER_ROLE, body.get("userRole"));
        putIfPresent(initial, GraphStateKeys.SESSION_ID, body.get("sessionId"));
        putIfPresent(initial, GraphStateKeys.USER_IMAGE_URLS, body.get("userImageUrls"));
        putIfPresent(initial, GraphStateKeys.SELECTED_FEATURE_NAME, body.get("selectedFeatureName"));

        Optional<OverAllState> result = mainGraph.invoke(initial);

        Map<String, Object> response = new HashMap<>();
        if (result.isPresent()) {
            OverAllState s = result.get();
            response.put("finalAnswer",
                    s.value(GraphStateKeys.FINAL_ANSWER, String.class).orElse("(empty)"));
            response.put("intent", s.value(GraphStateKeys.INTENT).orElse(null));
            response.put("matchedFeature",
                    s.value(GraphStateKeys.MATCHED_FEATURE, String.class).orElse(null));
            response.put("phaseLatencies",
                    s.value(GraphStateKeys.PHASE_LATENCIES).orElse(Map.of()));
            response.put("phaseLog", s.value(GraphStateKeys.PHASE_LOG).orElse(List.of()));
            response.put("rawState", s.data());  // 调试用, 第三刀去掉
        } else {
            response.put("error", "Graph returned empty");
        }
        return response;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}