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
    /** 历史消息列表 (List<ChatMessage>): 多轮对话上下文, 由 Controller 加载注入 */
    public static final String HISTORY_MESSAGES = "historyMessages";

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
    /** 来源元信息 (List&lt;com.wzh.graph.support.SourceInfo&gt;) */
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

    // ==================== 语义缓存 (第3刀) ====================

    /**
     * 缓存命中标记 + 命中的 cacheKey.
     * 非 null = L1/L2 命中, FinalizeNode 据此识别命中分支跳过 metrics 桥接的中间环节部分.
     * CacheCheckNode 写, Controller doOnNext 捕获, handleDone 写入 chat_message.cache_key.
     */
    public static final String CACHE_HIT_KEY = "cacheHitKey";

    /**
     * 缓存命中层级 "L1"/"L2"; 用于埋点和日志, 命中时 Controller 写入 chat_message.cache_hit_layer.
     */
    public static final String CACHE_HIT_LAYER = "cacheHitLayer";

    /**
     * 当前调用是否来自"重新生成"按钮 (Boolean; 缺省=false).
     *
     * <p><b>由 Controller 写入 initial state</b>: regenerate 分支才 put true, 普通对话不传.</p>
     *
     * <p><b>由 CacheCheckNode 读取</b>: true 时强制 MISS, 不走 L1/L2 查询. 设计动机:
     * 用户点"重新生成"= 显式拒绝了上一次答案 (无论该答案是 RAG 新生成还是缓存命中). 此时若
     * 再次命中同一缓存只会原样返回老答案, 等于把用户钉死在他刚拒绝的回答上.</p>
     *
     * <p><b>handleDone 的写缓存判定独立处理 isRegenerate</b>, 此 state key 仅用于 CacheCheckNode
     * 跳过查询; 写缓存判定见 {@code handleDone} 中的 {@code shouldCacheWrite} 条件.</p>
     */
    public static final String IS_REGENERATE = "isRegenerate";

    /**
     * B5: 工单按钮场景标记 — 老 assistant 消息 id (Long).
     *
     * <p><b>由 Controller 写入 initial state</b>: 按钮端点 {@code /api/agent/submit-ticket-for-message}
     * 入口处, 把前端传来的 targetAssistantMessageId 放进 state. 普通对话 (chatStream) 不传, 不放.</p>
     *
     * <p><b>由 TicketAgentNode 读取</b>: 在 {@code buildToolContext} 里塞进 toolContext map,
     * 透传给 MCP {@code submitTicket} 工具的 McpMeta. MCP 端工单创建成功后, 把这个 id 当作
     * "应该被回填 submitted_ticket_id 的消息 id"传给 main 的 {@code /internal/ticket/callback}.</p>
     *
     * <p><b>跟 isRegenerate 一样独立的 state 字段</b>, 因为按钮场景下:
     * <ul>
     *   <li>cache_check 应正常跑 (新 query "提交工单" 会 MISS, 走 RAG 后到 ticket_agent)</li>
     *   <li>cache-write 应跳过 (ticket 答复不应进缓存; 跟 IS_TICKET_RESPONSE 配合)</li>
     *   <li>负反馈不在跑 Graph 前打 (跟 regenerate 不同: 工单成功的事实只有 MCP 回调时才确定)</li>
     * </ul></p>
     */
    public static final String TICKET_BUTTON_TRIGGERED_BY = "ticketButtonTriggeredBy";

    /**
     * B5: 工单 Agent 响应标记 (Boolean).
     *
     * <p><b>由 TicketAgentNode 写入</b>: 节点执行完毕时 partial 里 put true,
     * 表示本轮响应是工单 Agent 生成的 (含"已为您提交工单 TK-xxx"/"提交失败"等).</p>
     *
     * <p><b>由 Controller handleDone 读取</b>: cache-write 判定加 {@code !isTicketResponse} 条件,
     * 工单结果不进语义缓存. 这同时覆盖了对话工单和按钮工单两种场景, 是借 B5 顺手修的存量 bug:
     * 之前对话工单的"已为您提交工单 TK-xxx"会被错误写入缓存, 下次同 query 命中会返回老工单号.</p>
     */
    public static final String IS_TICKET_RESPONSE = "isTicketResponse";

    // ========================================================================
    // Self-RAG 自反思 (最后一刀)
    // ========================================================================

    /**
     * Self-RAG: 跳过语义缓存写入标记 (Boolean).
     *
     * <p><b>由 KnowledgeAnswerNode 写入</b>: 当 Self-RAG 判定两版答案都不合格 (假问题,
     * winner_acceptable=false), FINAL_ANSWER 被置为兜底话术, 同时本字段 put true。</p>
     *
     * <p><b>由 Controller handleDone 读取</b>: cache-write 判定加 {@code !selfRagSkipCache} 条件,
     * 兜底话术("没找到相关信息")绝不写入缓存 — 否则下次同 query 命中会把"没找到"固化返回,
     * 与 B4 失效策略、命中即"已 PASS 答案"的缓存语义直接冲突。</p>
     *
     * <p><b>状态残留铁律</b>: 本字段被 Controller 读, KnowledgeAnswerNode 所有出口分支
     * (PASS / 择优采纳 / 假问题兜底) 必须无条件显式 put (默认 false), 不依赖"不 put=默认空"。
     * 同 B3-c CacheCheckNode / Batch1 phaseLog 的教训。</p>
     */
    public static final String SELF_RAG_SKIP_CACHE = "selfRagSkipCache";

    /**
     * Self-RAG: 自评裁决结果 (String, 取 SelfRagJudgement.Verdict 名 / "DISABLED" / "GIVE_UP" 等).
     *
     * <p>仅用于可观测性与日志 (大屏 self_reflect 维度统计、面试 debug), 不参与路由。
     * 由 KnowledgeAnswerNode 写入, 取值: DISABLED / PASS / RETRY_GEN_WIN_A / RETRY_GEN_WIN_B /
     * RETRY_RETRIEVE_WIN_A / RETRY_RETRIEVE_WIN_B / GIVE_UP。</p>
     */
    public static final String SELF_RAG_VERDICT = "selfRagVerdict";

    private GraphStateKeys() {
        // 禁止实例化
    }
}