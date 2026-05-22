package com.wzh.service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.wzh.config.DashScopeConfig;
import com.wzh.graph.support.GraphMetricsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 图片理解服务
 * 调用通义千问 VL 多模态模型，将图片转为文字描述
 *
 * 两种使用场景：
 * 1. 文档学习场景：analyzeImages / analyzeImage — 将文档截图转为可检索的文本知识
 * 2. 用户对话场景：analyzeUserScreenshot — 理解用户上传的截图，提取错误信息和界面状态
 *
 * <p><b>B2 token 埋点</b>: 两个对外方法在 result 拿到后都喂给 metricsCollector,
 * scene 分别为 IMAGE_DOC_LEARN (文档学习) / IMAGE_USER_SCREENSHOT (用户截图).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageUnderstandingService {

    private final DashScopeConfig dashScopeConfig;
    /** B2: token 埋点采集器, 在 VL 模型调用末尾发射. */
    private final GraphMetricsCollector metricsCollector;

    // ==================== 文档学习场景 ====================

    /**
     * 分析单张文档图片，生成文字描述
     * 【方案三改造】新增 textContext 参数，将文档文字上下文传给 VL 模型
     *
     * @param imageUrl    图片的完整 URL（MinIO 地址）
     * @param featureName 所属功能名称
     * @param chunkType   所属板块（feature_intro / feature_detail / operation_guide / faq）
     * @param textContext 图片所在段落的文字上下文（可为 null）
     * @return 图片的文字描述
     */
    public String analyzeImage(String imageUrl, String featureName, String chunkType, String textContext) {
        try {
            String prompt = buildDocAnalysisPrompt(featureName, chunkType, textContext);

            MultiModalMessage userMessage = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(
                            Collections.singletonMap("image", imageUrl),
                            Collections.singletonMap("text", prompt)
                    ))
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getVisionModel())
                    .message(userMessage)
                    .build();

            MultiModalConversation conversation = new MultiModalConversation();
            MultiModalConversationResult result = conversation.call(param);

            String description = result.getOutput().getChoices().get(0)
                    .getMessage().getContent().get(0).get("text").toString();

            // B2: token 埋点 (scene = image_doc_learn, 文档学习场景)
            recordVlTokens(result, dashScopeConfig.getVisionModel(),
                    GraphMetricsCollector.MetricScene.IMAGE_DOC_LEARN);

            log.info("图片分析完成 [{}] - {}, 描述长度: {} 字符",
                    featureName, imageUrl.substring(imageUrl.lastIndexOf('/') + 1),
                    description.length());

            return description;

        } catch (Exception e) {
            log.error("图片分析失败: {} - {}", featureName, imageUrl, e);
            return String.format("【图片】%s - %s 的相关截图（图片分析暂不可用）", featureName, chunkType);
        }
    }

    /**
     * 批量分析文档图片
     * 【方案三改造】新增 textContext 参数
     * 逐张调用（避免并发过高触发限流），每张之间间隔 500ms
     *
     * @param imageUrls   图片 URL 列表
     * @param featureName 所属功能名称
     * @param chunkType   所属板块
     * @param textContext 图片所在段落的文字上下文（可为 null）
     * @return 每张图片对应的文字描述列表（顺序与 imageUrls 一一对应）
     */
    public List<String> analyzeImages(List<String> imageUrls, String featureName,
                                      String chunkType, String textContext) {
        List<String> descriptions = new ArrayList<>();

        for (int i = 0; i < imageUrls.size(); i++) {
            String url = imageUrls.get(i);
            log.info("正在分析图片 [{}/{}]: {}", i + 1, imageUrls.size(), url);

            String description = analyzeImage(url, featureName, chunkType, textContext);
            descriptions.add(description);

            // 防止限流：每张图片之间间隔 500ms（最后一张不需要等待）
            if (i < imageUrls.size() - 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return descriptions;
    }

    // ==================== 用户对话场景 ====================

    /**
     * 分析用户在对话中上传的截图
     * 与文档学习场景不同，此方法侧重提取：错误提示、界面状态、异常显示、功能模块判断
     *
     * @param imageUrl     用户上传的截图 URL
     * @param userQuestion 用户的问题文字（提供上下文）
     * @return 截图内容描述
     */
    public String analyzeUserScreenshot(String imageUrl, String userQuestion) {
        try {
            String prompt = buildUserScreenshotPrompt(userQuestion);

            MultiModalMessage userMessage = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(
                            Collections.singletonMap("image", imageUrl),
                            Collections.singletonMap("text", prompt)
                    ))
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getVisionModel())
                    .message(userMessage)
                    .build();

            MultiModalConversation conversation = new MultiModalConversation();
            MultiModalConversationResult result = conversation.call(param);

            String description = result.getOutput().getChoices().get(0)
                    .getMessage().getContent().get(0).get("text").toString();

            // B2: token 埋点 (scene = image_user_screenshot, 用户对话截图场景)
            recordVlTokens(result, dashScopeConfig.getVisionModel(),
                    GraphMetricsCollector.MetricScene.IMAGE_USER_SCREENSHOT);

            log.info("用户截图分析完成，描述长度: {} 字符", description.length());
            return description;

        } catch (Exception e) {
            log.error("用户截图分析失败: {}", imageUrl, e);
            return "用户上传了一张截图（图片分析暂不可用）";
        }
    }

    // ==================== Prompt 构建 ====================

    /**
     * 构建文档学习场景的图片分析提示词
     * 【方案三核心改造】当有文字上下文时，引导模型结合上下文理解图片
     *
     * @param featureName 功能名称
     * @param chunkType   板块类型
     * @param textContext 文字上下文（可为 null）
     */
    private String buildDocAnalysisPrompt(String featureName, String chunkType, String textContext) {
        String sectionName = switch (chunkType) {
            case "feature_intro" -> "功能简介";
            case "feature_detail" -> "功能描述";
            case "operation_guide" -> "操作指南";
            case "faq" -> "常见问题";
            case "faq_qa" -> "用户FAQ";
            default -> "功能说明";
        };

        if (textContext != null && !textContext.isBlank()) {
            // 有文字上下文时：结合上下文理解图片，输出带因果关系的描述
            String contextSnippet = textContext.length() > 500 ?
                    textContext.substring(0, 500) + "..." : textContext;

            return String.format("""
                    这是一张软件产品功能截图，属于「%s」功能的「%s」部分。
                    
                    以下是这张截图所在段落的文字描述：
                    ---
                    %s
                    ---
                    
                    请结合上面的文字描述来理解这张截图，重点分析：
                    1. 截图展示的是文字描述中提到的哪个操作步骤或功能场景
                    2. 截图中的界面元素（按钮、菜单、输入框、表格等）和它们的文字标签
                    3. 如果截图展示的是错误提示或弹窗，请完整描述提示内容，并结合文字说明解释错误原因和解决方法
                    4. 如果截图展示的是操作步骤中的某一步，请说明这是第几步、做什么操作
                    
                    请用简洁准确的中文描述，重点突出对用户解决问题有帮助的信息。
                    """, featureName, sectionName, contextSnippet);
        } else {
            // 没有文字上下文时的降级 prompt
            return String.format("""
                    这是一张软件产品功能截图，属于「%s」功能的「%s」部分。
                    请详细描述这张截图中的内容，包括但不限于：
                    1. 界面整体布局和主要区域划分
                    2. 可见的按钮、菜单项、输入框等交互元素及其文字标签
                    3. 显示的数据内容、表格、列表等信息
                    4. 任何提示文字、标注或说明信息
                    5. 如果有错误提示或弹窗，请完整描述其内容
                    
                    请用简洁准确的中文描述。
                    """, featureName, sectionName);
        }
    }

    /**
     * 构建用户对话场景的截图分析提示词
     * 侧重提取错误信息、异常状态，帮助后续 RAG 检索
     */
    private String buildUserScreenshotPrompt(String userQuestion) {
        String questionContext = (userQuestion != null && !userQuestion.isBlank()) ?
                "用户的问题是：" + userQuestion + "\n\n" : "";

        return questionContext + """
                请分析这张软件截图，重点提取以下信息：
                1. 当前处于哪个功能模块或页面
                2. 是否有错误提示、警告弹窗或异常信息？如果有，请完整描述提示内容
                3. 当前界面的操作状态（正在进行什么操作、哪些选项被选中等）
                4. 是否有明显的异常显示（数据为空、加载失败、界面错乱等）
                
                请用简洁准确的中文描述，重点突出与用户问题相关的信息。
                不要使用"这张图片展示了"之类的开头，直接描述内容。
                """;
    }

    // ==================== B2 token 埋点 ====================

    /**
     * 从 MultiModalConversationResult 提 Usage 喂给 metricsCollector.
     *
     * <p><b>DashScope VL 模型的 Usage 字段</b>: inputTokens / outputTokens / imageTokens.
     * 我们只采 inputTokens + outputTokens 进 prompt/completion 计数,
     * imageTokens (图像 token, 与文本 token 同口径) 算入 prompt 端 — 这是行业惯例.</p>
     *
     * <p><b>B2 hotfix: completion 减法兜底</b>: 与 DashScopeService 同思路 —
     * 当 outputTokens=0 且 totalTokens > 输入侧 token 总和时, 用减法兜底.</p>
     *
     * <p><b>null 安全</b>: 任何环节 null 直接 return, 不抛出. 多模态调用本身已经在外层 try-catch 内,
     * 这里再加一层防御性返回, 确保埋点失败绝不影响业务返回值.</p>
     */
    private void recordVlTokens(MultiModalConversationResult result, String model, String scene) {
        if (metricsCollector == null || result == null || result.getUsage() == null) return;
        try {
            Object usage = result.getUsage();
            // 反射兼容: DashScope SDK VL 的 Usage 内嵌类字段名是 inputTokens/outputTokens/imageTokens,
            // getter 形如 getInputTokens(). 用反射兜底防 SDK 版本飘.
            long inputTokens = invokeIntGetterOrZero(usage, "getInputTokens");
            long outputTokens = invokeIntGetterOrZero(usage, "getOutputTokens");
            long imageTokens = invokeIntGetterOrZero(usage, "getImageTokens");
            long totalTokens = invokeIntGetterOrZero(usage, "getTotalTokens");
            // 图像 token 计入 prompt 侧 (用户输入消耗)
            long promptTokens = inputTokens + imageTokens;
            // 减法兜底: VL 模型某些场景下 outputTokens 也可能为 0
            if (outputTokens == 0 && totalTokens > promptTokens) {
                outputTokens = totalTokens - promptTokens;
            }
            metricsCollector.recordLlmTokens(model, scene, "n/a", promptTokens, outputTokens);
        } catch (Exception e) {
            log.warn("[VL] token 埋点失败 model={} scene={}", model, scene, e);
        }
    }

    /**
     * 反射调用形如 getXxxTokens() 的方法, 返回 long; 失败/null/非数值都返回 0.
     */
    private static long invokeIntGetterOrZero(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            if (value instanceof Integer i) return i.longValue();
            if (value instanceof Long l) return l;
            return 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }
}