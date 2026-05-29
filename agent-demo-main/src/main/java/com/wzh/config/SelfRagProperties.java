package com.wzh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Self-RAG 自反思配置 (最后一刀 / Self-RAG Batch 1).
 *
 * <p><b>背景与设计</b>: 知识问答主路径 (knowledge_answer) 存在"使用方式类 (how_to)
 * 回答质量不稳定"的痛点 — LLM 偶尔答非所问、步骤不完整、或脱离检索 context 自由发挥。
 * Self-RAG 在 knowledge_answer 节点内引入 <b>生成后自评 + best-of-2 择优</b> 机制:</p>
 *
 * <pre>
 *   生成第1版 (同步, 不推流)
 *     → judge 三维评估 (grounded / relevant / complete)
 *         ├─ 全 PASS 且高置信 → 直接采纳第1版 (收敛优化, 好问题不付 best-of-2 的成本)
 *         └─ 任一维不过       → 生成第2版 → judge pairwise 对比 → 择优
 *                                 └─ 两版都判劣 (假问题) → 返回兜底话术, 不写缓存
 * </pre>
 *
 * <p><b>为什么是 best-of-2 而非"重试循环"</b>: 单纯重试 (评第1版不过 → 重生成 → 无条件采纳第2版)
 * 存在"第2版可能更差却被迫采纳"的风险。改为"无条件 (或按需) 生成两版 → judge 二选一",
 * 永远取较好的那版, 消除了倒退风险。因此<b>没有 max-retry 参数</b> — 上限固定为 2 版。</p>
 *
 * <p><b>complete 维度对三类业务意图全部生效, 但判别标准按意图区分</b> (见 SelfRagEvaluator):
 * HOW_TO 看步骤是否完整、TROUBLESHOOT 看原因+解法是否完整、FEATURE_INTRO 看定义/用途是否说清。
 * 不存在"某类意图跳过 complete"的开关 — 三维对业务意图一律评估。</p>
 *
 * <p><b>与流式 SSE 的关系</b>: Self-RAG 要求"答案完整后才能评估", 与"边生成边推流"天然冲突。
 * 故 knowledge 路径转<b>同步生成</b> (内部 Buffer), 自评择优后再由前端模拟流式回放
 * (Internal Buffer + Replay SSE 架构)。本配置类只管自评逻辑, 不涉及推流。</p>
 *
 * <p><b>判别模型</b>: judge 用 qwen-plus (比 intent/rewrite 的 qwen-turbo 强, 判别更稳),
 * 走低 temperature 保证确定性。模型与温度均可配, 不写死。</p>
 *
 * <p><b>yml 示例</b>:
 * <pre>
 * self-rag:
 *   enabled: true
 *   judge-model: qwen-plus
 *   judge-temperature: 0.1
 *   judge-max-tokens: 400
 *   judge-timeout-ms: 8000
 *   give-up-fallback: "根据现有知识库, 我没有找到关于您问题的可靠信息..."
 * </pre></p>
 *
 * @author wzh
 * @since 2026-05-28 (Self-RAG Batch 1)
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "self-rag")
public class SelfRagProperties {

    /**
     * 总开关. false = knowledge_answer 节点退回"单版同步生成"(不自评、不 best-of-2),
     * 行为等同于 Self-RAG 引入之前 (除了由流式改同步)。
     * <p>demo 演示对比 "有/无 Self-RAG" 时一键切换。</p>
     */
    private boolean enabled = true;

    /**
     * judge 判别模型. 默认 qwen-plus。
     * <p>独立于业务生成模型 (业务也用 qwen-plus 但 temperature=0.7);
     * judge 需要确定性判别, 故单独建 client + 低温。</p>
     */
    private String judgeModel = "qwen-plus";

    /**
     * judge 调用温度. 判别是确定性场景, 设低 (0.1) 减少打分抖动。
     */
    private double judgeTemperature = 0.1;

    /**
     * judge 单次调用最大输出 token. 判别结果是短 JSON, 400 足够。
     */
    private int judgeMaxTokens = 400;

    /**
     * judge 调用超时 (毫秒). 超时视为判别失败 → 降级直接采纳第1版 (见 SelfRagEvaluator 容错)。
     * <p>不阻塞用户: judge 挂了不该让整条链路卡死, 宁可放过也不卡住。</p>
     */
    private long judgeTimeoutMs = 8000;

    /**
     * 假问题兜底话术. 当两版答案都被 judge 判定"不合格"(winner_acceptable=false),
     * 或第1版 grounded=false 且无检索 context 可重写时返回此文案, <b>且不写入语义缓存</b>。
     *
     * <p>设计动机: 知识库里压根没有这条信息时, 与其硬返回一个 LLM 编造的劣质答案 (并被缓存固化),
     * 不如诚实告知未找到 + 引导工单。这与 B4 失效策略、命中即"已 PASS 答案"的缓存语义一致。</p>
     */
    private String giveUpFallback =
            "抱歉, 根据现有知识库, 我没有找到与您问题直接相关的可靠信息。"
                    + "您可以尝试换个说法描述问题, 或提交工单由人工为您跟进。";
}