package com.wzh.graph.node;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.graph.core.GraphStateKeys;
import com.wzh.service.ImageUnderstandingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预处理节点 (3.A 升级版).
 *
 * <p><b>职责</b>: 把用户原始 query (+图片) 转成下游可用的 enhancedMessage.</p>
 *
 * <p><b>3.A 升级</b>: 注入 ImageUnderstandingService, 对每张用户图片调
 * analyzeUserScreenshot(), 把描述拼接到 enhancedMessage 末尾,
 * 行为对齐 AgentService.chatStream() Step 1.</p>
 *
 * <p><b>异常处理</b>: 单张图片理解失败不影响整体流程, 只 warn 日志.</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreprocessNode extends AbstractGraphNode {

    private static final String NODE_ID = "preprocess";

    private final ImageUnderstandingService imageUnderstandingService;

    @Override
    protected String nodeId() {
        return NODE_ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doApply(OverAllState state) {
        String userMessage = state.value(GraphStateKeys.USER_MESSAGE, String.class).orElse("");
        List<String> imageUrls = state.value(GraphStateKeys.USER_IMAGE_URLS, List.class).orElse(null);

        String enhancedMessage = userMessage;
        int analyzedCount = 0;
        if (imageUrls != null && !imageUrls.isEmpty()) {
            StringBuilder imageContext = new StringBuilder();
            for (String imageUrl : imageUrls) {
                try {
                    String desc = imageUnderstandingService.analyzeUserScreenshot(imageUrl, userMessage);
                    if (StrUtil.isNotBlank(desc)) {
                        imageContext.append("【用户截图内容】").append(desc).append("\n");
                        analyzedCount++;
                    }
                } catch (Exception e) {
                    log.warn("[{}] 用户截图理解失败 url={} err={}", NODE_ID, imageUrl, e.getMessage());
                }
            }
            if (!imageContext.isEmpty()) {
                enhancedMessage = userMessage + "\n\n" + imageContext;
            }
        }

        Map<String, Object> partial = new HashMap<>();
        partial.put(GraphStateKeys.ENHANCED_MESSAGE, enhancedMessage);

        log.info("[{}] userMessage='{}' images={}/{} → enhancedMessage.len={}",
                NODE_ID, userMessage,
                analyzedCount, imageUrls == null ? 0 : imageUrls.size(),
                enhancedMessage.length());
        appendPhaseLog(state, partial,
                "[" + NODE_ID + "] images=" + (imageUrls == null ? 0 : imageUrls.size())
                        + " analyzed=" + analyzedCount);
        return partial;
    }
}