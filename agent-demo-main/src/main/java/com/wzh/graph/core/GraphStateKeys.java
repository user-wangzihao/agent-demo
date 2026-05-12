package com.wzh.graph.core;

/**
 * 主对话 Graph 的 State Key 常量集中定义.
 *
 * <p><b>设计原则</b>:
 * <ul>
 *   <li>命名对齐 AgentService 现有字段语义, 不发明新词 (matchedFeature / intent / sources 等)</li>
 *   <li>所有 key 集中在这一个类里, 避免散落在各 Node 里硬编码字符串</li>
 *   <li>分组用注释隔开, 便于阅读和维护</li>
 * </ul></p>
 *
 * <p><b>分组说明</b>:
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │ 输入类   - 外部传入, 节点只读                              │
 * │ 预处理   - PreprocessNode 写入                           │
 * │ 意图     - IntentNode 写入                              │
 * │ Feature - FeatureResolveNode 写入                       │
 * │ 检索     - RagRetrieveNode (Doc) / FaqRetrieveNode 写入  │
 * │ 生成     - KnowledgeAnswerNode 写入                      │
 * │ 可观测性 - 所有节点累加写入 (read-modify-write 模式)         │
 * └─────────────────────────────────────────────────────┘
 * </pre></p>
 *
 * @author wzh
 * @since 2026-05-11
 */
public final class GraphStateKeys {

    // ==================== 输入类 (外部传入) ====================

    /** 复用 OverAllState.DEFAULT_INPUT_KEY = "input"; 这里取个别名指代用户原文 */
    public static final String USER_MESSAGE = "userMessage";
    /** 当前用户 ID (Long) */
    public static final String USER_ID = "userId";
    /** 当前用户登录名 (String) */
    public static final String USER_NAME = "userName";
    /** 用户角色: admin / user */
    public static final String USER_ROLE = "userRole";
    /** 当前会话 ID (Long); 为 null 表示新建会话 */
    public static final String SESSION_ID = "sessionId";
    /** 用户上传的图片 URL 列表 (List<String>) */
    public static final String USER_IMAGE_URLS = "userImageUrls";
    /** 前端用户主动选择的 feature_name (String); 可空 */
    public static final String SELECTED_FEATURE_NAME = "selectedFeatureName";

    // ==================== 预处理结果 (PreprocessNode) ====================

    /**
     * 拼接图片描述后的增强 query.
     * 第二刀: 直接 = USER_MESSAGE.
     * 第三刀: 真接 ImageUnderstandingService.analyzeUserScreenshot() 输出.
     */
    public static final String ENHANCED_MESSAGE = "enhancedMessage";

    // ==================== 意图识别结果 (IntentNode) ====================

    /** Intent 枚举 (com.wzh.enums.Intent) */
    public static final String INTENT = "intent";
    /** 意图识别来源: rule / llm */
    public static final String INTENT_SOURCE = "intentSource";
    /** 意图识别置信度 (Double 0~1) */
    public static final String INTENT_CONFIDENCE = "intentConfidence";

    // ==================== Feature 解析结果 (FeatureResolveNode) ====================

    /** 命中的 feature_name; 未命中 = null */
    public static final String MATCHED_FEATURE = "matchedFeature";

    // ==================== 检索结果 ====================

    /** 文档检索结果 (List<MilvusService.SearchResult>); 第二刀: 空 List */
    public static final String RETRIEVED_DOC_CHUNKS = "retrievedDocChunks";
    /** FAQ 检索结果 (List<MilvusService.SearchResult>); 第二刀&第三刀: 空 List, 第四刀真填 */
    public static final String RETRIEVED_FAQ_CHUNKS = "retrievedFaqChunks";
    /** 合并去重后的相关图片 URL (List<String>) */
    public static final String RELATED_IMAGES = "relatedImages";
    /** 来源元信息 (List<AgentService.SourceInfo>) */
    public static final String SOURCES = "sources";

    // ==================== 生成结果 (KnowledgeAnswerNode) ====================

    /** 最终回答文本 (String) */
    public static final String FINAL_ANSWER = "finalAnswer";

    // ==================== 可观测性 ====================

    /**
     * 各节点耗时 (Map<String, Long>, 节点名 → 毫秒).
     * 用 ReplaceStrategy + 节点内 read-modify-write 实现累加.
     */
    public static final String PHASE_LATENCIES = "phaseLatencies";
    /**
     * 各节点处理摘要 (List<String>, 一行一条).
     * 同样 ReplaceStrategy + read-modify-write.
     */
    public static final String PHASE_LOG = "phaseLog";

    private GraphStateKeys() {
        // 禁止实例化
    }
}