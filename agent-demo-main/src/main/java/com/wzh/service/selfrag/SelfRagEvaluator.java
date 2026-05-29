package com.wzh.service.selfrag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.config.SelfRagProperties;
import com.wzh.enums.Intent;
import com.wzh.graph.support.GraphMetricsCollector;
import com.wzh.model.selfrag.SelfRagComparison;
import com.wzh.model.selfrag.SelfRagJudgement;
import com.wzh.service.DashScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Self-RAG 判别器 (Self-RAG Batch 1).
 *
 * <p>用 qwen-plus 对 knowledge_answer 生成的答案做两类判别:
 * <ol>
 *   <li>{@link #judge}   - 单版三维诊断 (grounded / relevant / complete) → 决定 PASS 或如何生成第2版</li>
 *   <li>{@link #compare} - 两版 pairwise 对比择优 (winner + winner_acceptable) → best-of-2</li>
 * </ol>
 *
 * <p><b>三维对三类业务意图 (HOW_TO / TROUBLESHOOT / FEATURE_INTRO) 全部生效</b>。
 * complete 维度不存在"跳过"开关, 而是按意图给出<b>不同的判别标准</b>:
 * <ul>
 *   <li>HOW_TO        → 操作步骤是否完整可照做</li>
 *   <li>TROUBLESHOOT  → 原因分析 + 解决方案是否完整</li>
 *   <li>FEATURE_INTRO → 功能的定义/用途/价值是否说清楚 (而非要求"步骤", 避免误判)</li>
 *   <li>其它 (DEFAULT 等) → 是否充分回答, 不敷衍不残缺</li>
 * </ul>
 * 这样三类都评 complete, 但 FEATURE_INTRO 不会因"没有步骤"被误判不完整。</p>
 *
 * <p><b>复用 {@link DashScopeService#chatOnce} 而非自建 ChatClient bean</b>: 项目里 QueryRewrite /
 * Rerank / IntentClassify 都走 chatOnce 做"指定模型的一次性确定性调用", judge 同属此类。
 * 复用的好处: 自动获得 JSON Mode、token 埋点 (scene 标签)、统一的失败语义, 不引入新的 bean 装配。</p>
 *
 * <p><b>容错原则: judge 失败时"放过", 不卡用户</b>。judge 是质量增强而非准入门槛 — 它自己挂了
 * (超时/网络/JSON 解析失败) 不该让用户拿不到答案。故:
 * <ul>
 *   <li>{@link #judge} 失败 → 返回 {@link SelfRagJudgement#passOnError} (三维全过, verdict=PASS, 采纳第1版)</li>
 *   <li>{@link #compare} 失败 → 返回 {@link SelfRagComparison#pickFirstOnError} (选第1版且视为可接受)</li>
 * </ul>
 * 即 judge 的故障永远偏向"信任已有答案", 绝不偏向"触发兜底话术"(那会把合格答案换成"没找到")。</p>
 *
 * @author wzh
 * @since 2026-05-28 (Self-RAG Batch 1)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfRagEvaluator {

    private final SelfRagProperties props;
    private final DashScopeService dashScopeService;
    private final ObjectMapper objectMapper;
    private final GraphMetricsCollector metricsCollector;

    // =========================================================================
    // System Prompt — 单版三维诊断
    // =========================================================================

    /**
     * 单版判别系统提示词.
     *
     * <p><b>设计</b>: 让 LLM 只输出三个布尔 + reason, <b>不让它输出 verdict</b>
     * (verdict 由节点侧按规则映射, 避免 LLM 自由发挥导致路由不可控)。
     * complete 维度的判别标准由占位符按意图注入 (见 {@link #completeDescFor})。</p>
     */
    private static final String JUDGE_SYSTEM_PROMPT = """
        你是一个严格的答案质量审查员。给定【用户问题】【检索到的资料】【待审查答案】,
        你需要从三个维度独立判断答案质量, 只输出布尔值, 不要宽容打分。

        三个维度:
        1. grounded (是否基于资料): 答案的关键信息是否来自【检索到的资料】。
           - 若答案大量编造资料中没有的内容, 或与资料矛盾 → false
           - 若资料为空且答案仍在具体作答 (而非说"未找到") → false
        2. relevant (是否切题): 答案是否回答了【用户问题】真正问的点。
           - 答非所问、答了相关但不是用户问的 → false
        3. complete (是否完整): %s

        判断原则: 宁严勿宽。任一维度有明显问题就判 false, 这会触发系统重新生成更好的答案。

        【输出格式】严格输出 JSON, 不要任何解释、不要 markdown 代码块包裹:
        {"grounded": true/false, "relevant": true/false, "complete": true/false, "reason": "一句话说明"}
        """;

    /** complete 维度对操作类意图 (HOW_TO) 的判别标准 */
    private static final String COMPLETE_DESC_HOW_TO =
            "本问题是操作指引类, 答案应给出完整、可照做的操作步骤; 缺步骤、含糊带过、跳过关键环节 → false";
    /** complete 维度对排查类意图 (TROUBLESHOOT) 的判别标准 */
    private static final String COMPLETE_DESC_TROUBLESHOOT =
            "本问题是故障排查类, 答案应给出完整的原因分析和解决方案; 只描述现象不给解法、解法不完整 → false";
    /** complete 维度对功能介绍类意图 (FEATURE_INTRO) 的判别标准 */
    private static final String COMPLETE_DESC_FEATURE_INTRO =
            "本问题是功能介绍类, 答案应把功能的定义/用途/价值说清楚; 答得过于敷衍、一句话带过、没讲清是什么 → false";
    /** complete 维度对其它意图 (DEFAULT 等) 的判别标准 */
    private static final String COMPLETE_DESC_GENERAL =
            "答案应充分回答用户问题, 不应敷衍、不应残缺; 不充分则 false";

    // =========================================================================
    // System Prompt — 两版 pairwise 对比
    // =========================================================================

    /**
     * pairwise 对比系统提示词.
     *
     * <p><b>设计</b>: 让 judge 直接二选一 (相对判断, 比绝对打分稳定) + 判断赢的那版是否达标
     * (假问题防御)。同样不让它输出多余字段。</p>
     */
    private static final String COMPARE_SYSTEM_PROMPT = """
        你是一个严格的答案质量审查员。给定【用户问题】【检索到的资料】和【答案A】【答案B】,
        请判断哪一版回答得更好 (更切题、更基于资料、更完整可用), 并判断胜出的那版是否达到了可接受的质量。

        评判标准 (按重要性排序):
        1. 是否基于资料作答, 不编造
        2. 是否切中用户真正问的点
        3. 回答是否完整 (操作类看步骤、排查类看解法、介绍类看是否讲清)
        4. 表述是否清晰

        关于 winner_acceptable: 即使选出了较好的一版, 若两版都没能基于资料真正回答问题
        (例如资料里压根没有相关信息, 两版都在含糊或编造) → winner_acceptable 输出 false。

        【输出格式】严格输出 JSON, 不要任何解释、不要 markdown 代码块包裹:
        {"winner": "A" 或 "B", "winner_acceptable": true/false, "reason": "一句话说明"}
        """;

    // =========================================================================
    // 公开入口
    // =========================================================================

    /**
     * 对单版答案做三维诊断, 并映射出 verdict。
     *
     * @param query   用户问题 (增强后的 query)
     * @param context 检索到的资料 (拼好的 retrievedContext, 可能为空串)
     * @param answer  待审查的第1版答案
     * @param intent  当前意图 (决定 complete 维度的判别标准)
     * @return 三维诊断 + verdict; judge 失败时返回 PASS 兜底 (放过第1版)
     */
    public SelfRagJudgement judge(String query, String context, String answer, Intent intent) {
        if (answer == null || answer.isBlank()) {
            // 空答案直接判重生成 (不浪费一次 judge 调用)
            SelfRagJudgement j = SelfRagJudgement.builder()
                    .grounded(false).relevant(false).complete(false)
                    .reason("第1版答案为空").build();
            j.setVerdict(SelfRagJudgement.Verdict.RETRY_GEN);
            return j;
        }

        String completeDesc = completeDescFor(intent);
        String systemPrompt = String.format(JUDGE_SYSTEM_PROMPT, completeDesc);
        String userPrompt = buildJudgeUserPrompt(query, context, answer);

        long start = System.currentTimeMillis();
        try {
            String raw = dashScopeService.chatOnce(
                    props.getJudgeModel(),
                    systemPrompt,
                    userPrompt,
                    (float) props.getJudgeTemperature(),
                    props.getJudgeMaxTokens(),
                    "json_object",
                    GraphMetricsCollector.MetricScene.SELF_REFLECT_JUDGE);

            SelfRagJudgement j = objectMapper.readValue(stripJson(raw), SelfRagJudgement.class);
            j.setVerdict(mapVerdict(j));

            log.info("[self-rag-judge] intent={} grounded={} relevant={} complete={} verdict={} reason={}",
                    intent == null ? "n/a" : intent.getCode(),
                    j.isGrounded(), j.isRelevant(), j.isComplete(),
                    j.getVerdict(), j.getReason());
            return j;

        } catch (Exception e) {
            // judge 失败 → 放过第1版, 不卡用户
            log.warn("[self-rag-judge] 判别失败, 降级为 PASS 放过第1版. err={}", e.getMessage());
            return SelfRagJudgement.passOnError("judge 调用/解析失败: " + e.getMessage());
        } finally {
            metricsCollector.recordReflectLatency("judge", System.currentTimeMillis() - start);
        }
    }

    /**
     * 两版答案 pairwise 对比择优。
     *
     * @param query    用户问题
     * @param context  检索到的资料 (对比时用第2版的 context, 因为重检索后 context 可能已变;
     *                 由调用方决定传哪一份)
     * @param answerA  第1版答案
     * @param answerB  第2版答案
     * @return 择优结果; 对比失败时返回"选 A 且可接受"兜底
     */
    public SelfRagComparison compare(String query, String context, String answerA, String answerB) {
        // 防御: 任一版为空, 直接选非空的那版 (不浪费 judge 调用)
        boolean aBlank = answerA == null || answerA.isBlank();
        boolean bBlank = answerB == null || answerB.isBlank();
        if (aBlank && bBlank) {
            return SelfRagComparison.builder()
                    .winner("A").winnerAcceptable(false)
                    .reason("两版答案均为空").build();
        }
        if (bBlank) return SelfRagComparison.pickFirstOnError("第2版为空, 采纳第1版");
        if (aBlank) {
            return SelfRagComparison.builder()
                    .winner("B").winnerAcceptable(true)
                    .reason("第1版为空, 采纳第2版").build();
        }

        String userPrompt = buildCompareUserPrompt(query, context, answerA, answerB);
        long start = System.currentTimeMillis();
        try {
            String raw = dashScopeService.chatOnce(
                    props.getJudgeModel(),
                    COMPARE_SYSTEM_PROMPT,
                    userPrompt,
                    (float) props.getJudgeTemperature(),
                    props.getJudgeMaxTokens(),
                    "json_object",
                    GraphMetricsCollector.MetricScene.SELF_REFLECT_JUDGE);

            SelfRagComparison c = objectMapper.readValue(stripJson(raw), SelfRagComparison.class);
            log.info("[self-rag-compare] winner={} acceptable={} reason={}",
                    c.getWinner(), c.isWinnerAcceptable(), c.getReason());
            return c;

        } catch (Exception e) {
            log.warn("[self-rag-compare] 对比失败, 降级为采纳第1版. err={}", e.getMessage());
            return SelfRagComparison.pickFirstOnError("compare 调用/解析失败: " + e.getMessage());
        } finally {
            metricsCollector.recordReflectLatency("compare", System.currentTimeMillis() - start);
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /**
     * 按意图返回 complete 维度的判别标准。三类业务意图各有标准, 不存在"跳过 complete"。
     */
    private String completeDescFor(Intent intent) {
        if (intent == Intent.HOW_TO)        return COMPLETE_DESC_HOW_TO;
        if (intent == Intent.TROUBLESHOOT)  return COMPLETE_DESC_TROUBLESHOOT;
        if (intent == Intent.FEATURE_INTRO) return COMPLETE_DESC_FEATURE_INTRO;
        return COMPLETE_DESC_GENERAL;
    }

    /**
     * 三维 → verdict 映射 (节点侧规则, 不交给 LLM)。
     * <ul>
     *   <li>全 true → PASS</li>
     *   <li>grounded=false → RETRY_RETRIEVE (context 不对路, 重检索)</li>
     *   <li>grounded=true 但 relevant/complete 有假 → RETRY_GEN (换 prompt 重生成)</li>
     * </ul>
     */
    private SelfRagJudgement.Verdict mapVerdict(SelfRagJudgement j) {
        if (j.isGrounded() && j.isRelevant() && j.isComplete()) {
            return SelfRagJudgement.Verdict.PASS;
        }
        if (!j.isGrounded()) {
            return SelfRagJudgement.Verdict.RETRY_RETRIEVE;
        }
        return SelfRagJudgement.Verdict.RETRY_GEN;
    }

    private String buildJudgeUserPrompt(String query, String context, String answer) {
        return "【用户问题】\n" + safe(query) + "\n\n"
                + "【检索到的资料】\n" + (isBlank(context) ? "(无检索资料)" : context) + "\n\n"
                + "【待审查答案】\n" + safe(answer);
    }

    private String buildCompareUserPrompt(String query, String context, String a, String b) {
        return "【用户问题】\n" + safe(query) + "\n\n"
                + "【检索到的资料】\n" + (isBlank(context) ? "(无检索资料)" : context) + "\n\n"
                + "【答案A】\n" + safe(a) + "\n\n"
                + "【答案B】\n" + safe(b);
    }

    /**
     * 剥离 LLM 偶尔输出的 ```json ... ``` 代码块包裹 (即使开了 JSON Mode 也兜底)。
     */
    private String stripJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            // 去掉首行 ```json / ``` 和尾部 ```
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        return s.isEmpty() ? "{}" : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}