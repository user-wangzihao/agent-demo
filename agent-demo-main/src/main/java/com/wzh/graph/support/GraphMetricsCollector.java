package com.wzh.graph.support;

import com.wzh.enums.Intent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * AgentDemo 业务指标采集器 (第 2 刀 B2 引入).
 *
 * <p><b>定位</b>: 所有自定义 Prometheus 指标的<b>唯一发射入口</b>. 业务代码 (Node / Service)
 * 不应直接持有 {@link MeterRegistry}, 改为依赖本类的 {@code recordXxx} 方法. 这样做的好处:
 * <ul>
 *   <li>指标命名、标签格式、单位约定集中在一处, 不会出现 4 个 Node 各写各的 Counter 名字
 *       (然后 Prometheus 端看到一堆同概念不同名字的指标)</li>
 *   <li>未来要加指标只改这一个类, 业务代码零侵入</li>
 *   <li>本类的方法签名就是"我们暴露了什么观测维度"的活文档</li>
 * </ul>
 *
 * <h2>指标命名约定</h2>
 * <p>统一前缀 {@code agent_*}, 用下划线分隔, 全小写. 这是 Prometheus 推荐命名:
 * <ul>
 *   <li>计数累加用 {@code _total} 结尾 (Counter)</li>
 *   <li>耗时秒数用 {@code _seconds} 结尾 (Timer; Micrometer 会自动转秒)</li>
 *   <li>纯数值分布不用后缀 (DistributionSummary)</li>
 * </ul>
 *
 * <h2>当前覆盖的指标族</h2>
 * <ol>
 *   <li><b>Graph 节点耗时</b> ({@link #recordNodeLatency}):
 *       Timer + Histogram, 维度 {@code node_name}. 用于 P50/P95/P99 分位数和热力图.</li>
 *   <li><b>意图分布</b> ({@link #recordIntent}):
 *       Counter, 维度 {@code intent_code} + {@code intent_source}.
 *       intent_source 区分 rule/llm/fallback, 反映分类质量.</li>
 *   <li><b>检索质量</b> ({@link #recordRetrievedDocChunks} / {@link #recordRetrievedFaqChunks}):
 *       DistributionSummary, 反映每次对话拿到的 chunk 数量分布.</li>
 *   <li><b>LLM token 消耗</b> ({@link #recordLlmTokens}):
 *       Counter, 维度 {@code model} + {@code scene} + {@code intent} + {@code token_type}.
 *       覆盖主对话/意图分类/改写/图像理解全场景.</li>
 *   <li><b>语义缓存</b> (B6, {@link #recordCacheHit} / {@link #recordCacheMiss} / {@link #recordCacheDegraded}):
 *       Counter, hit 维度 {@code layer} (L1/L2), miss/degraded 无标签. 故意不暴露 feature_name
 *       (700+ feature 会导致基数爆炸), 按 feature 维度走 SQL 直查.</li>
 * </ol>
 *
 * <h2>标签基数控制</h2>
 * <p>Prometheus 反高基数 — 每个新的标签值组合产生一个独立时序. 本类的所有标签都是<b>受控有限集</b>:
 * <ul>
 *   <li>{@code node_name}: 11 个固定节点名</li>
 *   <li>{@code intent_code}: 6 个枚举</li>
 *   <li>{@code intent_source}: rule/llm/fallback 3 个</li>
 *   <li>{@code scene}: 5 个常量 (见 {@link MetricScene})</li>
 *   <li>{@code model}: 受限于 yml 配置, 当前 4 个 (qwen-plus/qwen-turbo/qwen-vl-max/text-embedding-v3)</li>
 *   <li>{@code token_type}: prompt/completion/total 3 个</li>
 * </ul>
 * 不暴露 userId / sessionId / feature_name 等高基数维度作为标签 (这类需求走 B4 KPI 后端直查 DB).</p>
 *
 * @author wzh
 * @since 2026-05-21 (B2)
 */
@Slf4j
@Component
public class GraphMetricsCollector {

    private final MeterRegistry meterRegistry;

    /**
     * Spring AI Alibaba 1.0.0 实现下, {@code ChatResponse.getMetadata().getModel()} 经常返回 null,
     * 导致 token 指标 model 标签被打成 "unknown" — Grafana 看图时不知道是什么模型在烧 token.
     * 这里从 yml 读 chat 模型名作为兜底, 默认 qwen-plus, 与 yml 配置同义.
     * 仅对 chat_main scene 生效 (其他 scene 都用调用方传入的具体模型名).
     */
    @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String fallbackChatModel;

    // ==================== Meter 实例缓存 ====================
    // Micrometer 的 Counter/Timer 等是 immutable, 同 name+tags 会自动复用同一个实例.
    // 这里不做手动缓存, 每次 meterRegistry.counter(...) 调用查表/创建都是 O(1) + lock-free.

    public GraphMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("[GraphMetricsCollector] initialized, registry={}", meterRegistry.getClass().getSimpleName());
    }

    // ==================== A. Graph 节点耗时 ====================

    /**
     * 记录单个 Graph 节点的执行耗时.
     *
     * <p>开启 publishPercentileHistogram, Prometheus 端会暴露 {@code _bucket} 直方图,
     * 可在 Grafana 用 {@code histogram_quantile(0.95, rate(agent_graph_node_latency_seconds_bucket[5m]))}
     * 算 P95.</p>
     *
     * @param nodeName 节点 id (preprocess / intent / feature_resolve / doc_retrieve / faq_retrieve /
     *                 merger / chitchat_answer / knowledge_answer / ticket_agent / admin_agent / finalize)
     * @param costMs   节点执行耗时, 毫秒
     */
    public void recordNodeLatency(String nodeName, long costMs) {
        try {
            Timer.builder("agent_graph_node_latency_seconds")
                    .description("Graph 节点执行耗时分布")
                    .tag("node_name", safeTag(nodeName))
                    .publishPercentileHistogram()
                    // 自定义 SLO buckets (毫秒粒度); 覆盖节点常见耗时区间, 不依赖 Micrometer 默认的指数分布
                    .serviceLevelObjectives(
                            Duration.ofMillis(10),
                            Duration.ofMillis(50),
                            Duration.ofMillis(100),
                            Duration.ofMillis(250),
                            Duration.ofMillis(500),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(30))
                    .register(meterRegistry)
                    .record(Duration.ofMillis(costMs));
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordNodeLatency failed nodeName={} costMs={}",
                    nodeName, costMs, e);
            // 埋点失败绝不影响业务流程
        }
    }

    /**
     * 批量记录一整轮 Graph 执行的所有节点耗时. 由 FinalizeNode 在收尾时调用.
     */
    public void recordAllNodeLatencies(Map<String, Long> phaseLatencies) {
        if (phaseLatencies == null || phaseLatencies.isEmpty()) return;
        phaseLatencies.forEach(this::recordNodeLatency);
    }

    // ==================== B. 意图分布 ====================

    /**
     * 记录一次意图分类的结果.
     *
     * @param intent 分类结果 (CHITCHAT / HOW_TO / ...). 不能为 null (上游必然 fallback 到 DEFAULT).
     * @param source 分类来源: "rule" (关键词命中) / "llm" (LLM 给出) / "fallback" (兜底降级)
     */
    public void recordIntent(Intent intent, String source) {
        try {
            String intentCode = (intent == null) ? "default" : intent.getCode();
            Counter.builder("agent_intent_total")
                    .description("意图分类结果计数")
                    .tag("intent_code", safeTag(intentCode))
                    .tag("intent_source", safeTag(source == null ? "unknown" : source))
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordIntent failed intent={} source={}",
                    intent, source, e);
        }
    }

    // ==================== C. 检索质量 ====================

    /**
     * 记录单轮对话拿到的文档 chunk 数量分布.
     */
    public void recordRetrievedDocChunks(int count) {
        try {
            DistributionSummary.builder("agent_retrieved_doc_chunks")
                    .description("Doc retrieve 返回的 chunk 数量")
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(count);
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordRetrievedDocChunks failed count={}", count, e);
        }
    }

    /**
     * 记录单轮对话拿到的 FAQ chunk 数量分布. 配合 doc chunks 反映检索质量.
     *
     * <p>FAQ 命中率指标 (KPI 卡片) 在 B4 后端走 DB 直查, 不在这里用 Prometheus 算 —
     * "命中率" 是 ratio, 用 Counter 算需要分子分母两个时序再用 PromQL ratio,
     * 演示场景直接读 DB 更直观.</p>
     */
    public void recordRetrievedFaqChunks(int count) {
        try {
            DistributionSummary.builder("agent_retrieved_faq_chunks")
                    .description("FAQ retrieve 返回的 chunk 数量")
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(count);
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordRetrievedFaqChunks failed count={}", count, e);
        }
    }

    // ==================== D. LLM Token 消耗 ====================

    /**
     * 记录一次 LLM 调用的 token 消耗.
     *
     * <p><b>调用方约定</b>:
     * <ul>
     *   <li>主对话 (ChatClientInvoker): scene = chat_main, intent 传当前对话的 Intent.code()</li>
     *   <li>意图分类 (DashScopeService.chatOnce by LlmIntentClassifier): scene = intent_classify,
     *       intent 传 {@code "n/a"} (分类还没出结果, 没法标 intent)</li>
     *   <li>Query 改写 (DashScopeService.chatOnce by QueryRewriteService): scene = query_rewrite,
     *       intent 传当前对话的 Intent.code() 或 {@code "n/a"}</li>
     *   <li>用户截图理解 (ImageUnderstandingService.analyzeUserScreenshot): scene = image_user_screenshot,
     *       intent 传 {@code "n/a"} (截图发生在 intent 之前)</li>
     *   <li>文档学习 (ImageUnderstandingService.analyzeImage): scene = image_doc_learn,
     *       intent 传 {@code "n/a"} (管理员后台任务, 与对话 intent 无关)</li>
     * </ul></p>
     *
     * @param model           模型名 (qwen-plus / qwen-turbo / qwen-vl-max / ...)
     * @param scene           调用场景, 见 {@link MetricScene}
     * @param intentCode      当前对话的 intent.code() 或 "n/a"
     * @param promptTokens    输入 token 数 (允许 0)
     * @param completionTokens 输出 token 数 (允许 0)
     */
    public void recordLlmTokens(String model, String scene, String intentCode,
                                long promptTokens, long completionTokens) {
        try {
            // B2 hotfix: Spring AI Alibaba 1.0.0 偶尔不填 ChatResponse.metadata.model,
            // 这里把 null / blank / "unknown" 替换为 yml 配置的兜底 model 名 (默认 qwen-plus).
            // 仅影响 chat_main scene — 其他 scene (intent_classify/feature_extract/query_rewrite/image_*)
            // 调用方都已显式传入正确 model, 走不到这条兜底分支.
            String modelTag;
            if (model == null || model.isBlank() || "unknown".equalsIgnoreCase(model)) {
                modelTag = safeTag(fallbackChatModel);
            } else {
                modelTag = safeTag(model);
            }
            String sceneTag = safeTag(scene == null ? "unknown" : scene);
            String intentTag = safeTag(intentCode == null ? "n/a" : intentCode);

            Tags baseTags = Tags.of(
                    "model", modelTag,
                    "scene", sceneTag,
                    "intent", intentTag);

            if (promptTokens > 0) {
                meterRegistry.counter("agent_llm_tokens_total",
                                baseTags.and("token_type", "prompt"))
                        .increment(promptTokens);
            }
            if (completionTokens > 0) {
                meterRegistry.counter("agent_llm_tokens_total",
                                baseTags.and("token_type", "completion"))
                        .increment(completionTokens);
            }
            long total = promptTokens + completionTokens;
            if (total > 0) {
                meterRegistry.counter("agent_llm_tokens_total",
                                baseTags.and("token_type", "total"))
                        .increment(total);
            }
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordLlmTokens failed model={} scene={} intent={} " +
                            "promptTokens={} completionTokens={}",
                    model, scene, intentCode, promptTokens, completionTokens, e);
        }
    }

    // ==================== D. 语义缓存 (第3刀 B6) ====================
    //
    // 设计要点 — feature_name 故意不作为标签:
    //
    // 业务侧 feature 数量在 700+ 量级 (并随业务持续增长). 如果按 feature 拆分 Counter,
    // 仅 cache hit/miss/degraded 3 个指标就会产生 700×4 = 2800+ 时序,
    // Grafana 渲染卡 / PromQL 聚合开销大 / 稀疏时序污染.
    //
    // 解决: Prometheus 走"热路径轻量化", 只埋全局聚合 (4 个时序) 用于 SLO 告警和大屏命中率卡.
    // 按 feature 维度的细粒度分析走"冷路径", 调 SemanticCacheService 直接 SQL 聚合
    // (semantic_cache 表本身已有 hit_count / feedback_score / status 字段, 数据更精确).
    // 这是 high-cardinality 处理的标准实践 — 跟 Datadog/New Relic 的建议对齐.

    /** Cache 命中层级标签常量, 跟 SemanticCacheService.CacheLookupResult.hitLayer 对齐. */
    public static final String CACHE_LAYER_L1 = "L1";
    public static final String CACHE_LAYER_L2 = "L2";

    /**
     * 记录一次缓存命中.
     *
     * @param layer L1 (Redis 字面命中) / L2 (Milvus 语义命中). 由 CacheCheckNode 在命中分支调用.
     */
    public void recordCacheHit(String layer) {
        try {
            Counter.builder("agent_cache_hit_total")
                    .description("语义缓存命中次数, 按 L1/L2 拆分")
                    .tag("layer", safeTag(layer == null ? "unknown" : layer))
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordCacheHit failed layer={}", layer, e);
        }
    }

    /**
     * 记录一次缓存未命中 (CacheCheckNode 完整跑过 L1 + L2 都没命中, 即将走 RAG).
     *
     * <p>注意: CacheCheckNode 的"早退分支"(disabled / regenerate / featureName 空) 不计为 miss,
     * 因为它们根本没真正执行 lookup. miss 的语义是"查询了但没找到", 区分这两类对命中率统计很重要.</p>
     */
    public void recordCacheMiss() {
        try {
            Counter.builder("agent_cache_miss_total")
                    .description("语义缓存未命中次数 (lookup 真正执行后返回 null)")
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordCacheMiss failed", e);
        }
    }

    /**
     * 记录一次缓存条目被降级 (status: ACTIVE → DEGRADED), 由 SemanticCacheService.incrementFeedback
     * 在 feedback_score 达阈值时调用.
     *
     * <p>反映"哪些 feature 答案质量差"的早期信号: degraded_total 上涨快 = 用户连续吐槽某些答案,
     * 应该回查这些 feature 的文档/FAQ 质量.</p>
     */
    public void recordCacheDegraded() {
        try {
            Counter.builder("agent_cache_degraded_total")
                    .description("语义缓存条目被降级次数 (feedback_score 触阈)")
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordCacheDegraded failed", e);
        }
    }

    /**
     * 记录一次 Self-RAG 自反思裁决 (最后一刀). 由 KnowledgeAnswerNode 在每次知识问答收尾时调用.
     *
     * <p><b>verdict 取值 (受控有限集, 7 个, 基数安全)</b>:
     * <ul>
     *   <li>{@code DISABLED}              - self-rag 关闭, 单版同步生成</li>
     *   <li>{@code PASS}                  - 第1版三维全过, 直接采纳 (收敛优化命中)</li>
     *   <li>{@code RETRY_GEN_WIN_A/B}     - 召回对但生成不佳, 重生成第2版后择优 (选 A=第1版/B=第2版)</li>
     *   <li>{@code RETRY_RETRIEVE_WIN_A/B}- 召回不对路, 重检索+重生成后择优</li>
     *   <li>{@code GIVE_UP}               - 两版都不合格 (假问题), 返回兜底话术</li>
     * </ul>
     *
     * <p><b>这是"回答质量"的核心量化维度</b>:
     * <ul>
     *   <li>PASS 占比高 = 一次生成质量好; RETRY_* 占比 = 自反思的实际触发率与纠正效果</li>
     *   <li>WIN_B 占比 = 第2版战胜第1版的比例, 直接反映 Self-RAG 带来的质量提升幅度</li>
     *   <li>GIVE_UP 占比 = 假问题/知识盲区的暴露率, 指引知识库补充方向</li>
     * </ul>
     * 简历可写: "通过自反思裁决埋点量化 how-to 类回答的重生成纠正率 / 第2版胜出率"。</p>
     *
     * <p>故意只用 {@code verdict} 一个标签, 不带 feature_name/intent (高基数);
     * 需要按 feature 下钻时走 SQL 直查 (与 B6 缓存指标的热/冷路径分离设计一致)。</p>
     *
     * @param verdict 裁决标签 (见上); null/空兜底为 "unknown"
     */
    public void recordReflectVerdict(String verdict) {
        try {
            Counter.builder("agent_reflect_verdict_total")
                    .description("Self-RAG 自反思裁决分布 (PASS/RETRY_*/GIVE_UP/DISABLED)")
                    .tag("verdict", safeTag(verdict == null || verdict.isBlank() ? "unknown" : verdict))
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordReflectVerdict failed verdict={}", verdict, e);
        }
    }

    /**
     * 记录一次 Self-RAG judge LLM 调用耗时 (最后一刀, 思路B: 量化自反思延迟增量).
     *
     * <p>由 {@link com.wzh.service.selfrag.SelfRagEvaluator} 在每次 judge / compare 调用收尾时记录。
     * 与 {@code node_name="knowledge_answer"} 的节点 P95 配合解读:
     * <ul>
     *   <li>knowledge_answer 节点 P95 = 用户感受到的端到端知识问答耗时 (含生成+自反思)</li>
     *   <li>本指标 = 自反思中 <b>单次 judge LLM 调用</b> 的纯耗时, phase 标签区分 judge/compare</li>
     * </ul>
     * 两者相减可估算"自反思相对纯生成引入了多少延迟", 直接回答"你这 Self-RAG 加了多少延迟"。
     * 简历可写: "可观测性精确到自反思 judge 的延迟分位数, 量化质量增强的延迟成本"。</p>
     *
     * <p>phase 标签受控 (judge / compare 两值), 基数安全。</p>
     *
     * @param phase  "judge" (单版三维诊断) / "compare" (两版 pairwise 对比)
     * @param costMs judge LLM 调用耗时 (毫秒)
     */
    public void recordReflectLatency(String phase, long costMs) {
        try {
            Timer.builder("agent_reflect_latency_seconds")
                    .description("Self-RAG judge LLM 调用耗时分布, 按 judge/compare 拆分")
                    .tag("phase", safeTag(phase == null || phase.isBlank() ? "unknown" : phase))
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(
                            Duration.ofMillis(250),
                            Duration.ofMillis(500),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(8),
                            Duration.ofSeconds(15))
                    .register(meterRegistry)
                    .record(Duration.ofMillis(costMs));
        } catch (Exception e) {
            log.warn("[GraphMetricsCollector] recordReflectLatency failed phase={} costMs={}",
                    phase, costMs, e);
        }
    }

    // ==================== 内部 ====================

    /**
     * Prometheus 标签值必须是合法 UTF-8, 且不能有换行/双引号. 防御性兜底.
     * 实际业务标签值都是英文常量, 不会触发兜底, 但万一上游传脏字符避免整套时序污染.
     */
    private static String safeTag(String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        // 替换非字母数字下划线/连字符的字符为 _
        return raw.replaceAll("[^a-zA-Z0-9_/\\-]", "_");
    }

    // ==================== 场景常量 ====================

    /**
     * Token 埋点的 scene 标签常量, 集中定义避免散落.
     */
    public static final class MetricScene {
        /** 主对话 LLM 调用 (chitchat / knowledge / ticket / admin Answer Node 都走 ChatClientInvoker, 统一记 chat_main). */
        public static final String CHAT_MAIN = "chat_main";
        /** 意图分类 (LlmIntentClassifier → DashScopeService.chatOnce → qwen-turbo). */
        public static final String INTENT_CLASSIFY = "intent_classify";
        /** Query 改写 (QueryRewriteService → DashScopeService.chatOnce → qwen-turbo). */
        public static final String QUERY_REWRITE = "query_rewrite";
        /** Feature 名称提取 (FeatureExtractService → DashScopeService.chatOnce → qwen-turbo). */
        public static final String FEATURE_EXTRACT = "feature_extract";
        /** 用户对话截图理解 (ImageUnderstandingService.analyzeUserScreenshot → qwen-vl-max). */
        public static final String IMAGE_USER_SCREENSHOT = "image_user_screenshot";
        /** 文档学习场景的图像分析 (ImageUnderstandingService.analyzeImage → qwen-vl-max), 不在对话主链路. */
        public static final String IMAGE_DOC_LEARN = "image_doc_learn";
        /** Self-RAG 自反思判别 (SelfRagEvaluator → DashScopeService.chatOnce → qwen-plus), 含单版三维诊断 + 两版对比. */
        public static final String SELF_REFLECT_JUDGE = "self_reflect_judge";

        private MetricScene() {}
    }
}