package com.wzh.agentdemo.evaltools.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.model.AuditVerdict;
import lombok.extern.slf4j.Slf4j;

/**
 * 调 LLM 判断 chunk 是否能回答 query。
 */
@Slf4j
public class ChunkRelevanceJudge {

    private final DashScopeClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChunkRelevanceJudge(DashScopeClient client) {
        this.client = client;
    }

    private static final String PROMPT_TEMPLATE = """
            你是一个 RAG 评估辅助员。判断给定的"知识块"是否能回答"用户问题"。

            ## 用户问题
            %s

            ## 知识块内容
            %s

            ## 判定标准
            - YES: 知识块明确包含问题的答案，用户读完能解决问题
            - PARTIAL: 知识块包含部分相关信息，但不足以完整回答
            - NO: 知识块与问题无关或仅有边缘关联

            ## 输出要求
            只输出一个 JSON 对象，不要有任何其他文字、解释或 Markdown 代码块标记。
            JSON 格式严格如下:
            {"verdict": "YES" | "PARTIAL" | "NO", "confidence": 0.0~1.0 之间的小数, "reason": "30字以内的简短理由"}
            """;

    public AuditVerdict judge(String model, String query, String chunkContent) {
        String prompt = PROMPT_TEMPLATE.formatted(query, chunkContent);
        // 二分类任务用低温度
        String raw = client.chatWithRetry(model, prompt, 0.0);

        if (raw == null) {
            return AuditVerdict.builder()
                    .verdict(AuditVerdict.Verdict.ERROR)
                    .confidence(0.0)
                    .reason("LLM 调用失败")
                    .model(model)
                    .build();
        }

        return parseVerdict(raw, model);
    }

    /**
     * 解析 LLM 输出，处理常见的格式异常 (含 markdown fence、多余文字)。
     */
    private AuditVerdict parseVerdict(String raw, String model) {
        String cleaned = raw.trim();
        // 剥离 markdown code fence
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            if (firstNl > 0) cleaned = cleaned.substring(firstNl + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();
        }

        // 仅保留首个 JSON 对象 (LLM 偶尔会前后加解释)
        int braceStart = cleaned.indexOf('{');
        int braceEnd = cleaned.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            cleaned = cleaned.substring(braceStart, braceEnd + 1);
        }

        try {
            JsonNode node = mapper.readTree(cleaned);
            String verdictStr = node.path("verdict").asText("").trim().toUpperCase();
            double conf = node.path("confidence").asDouble(0.5);
            String reason = node.path("reason").asText("").trim();

            AuditVerdict.Verdict v;
            switch (verdictStr) {
                case "YES" -> v = AuditVerdict.Verdict.YES;
                case "PARTIAL" -> v = AuditVerdict.Verdict.PARTIAL;
                case "NO" -> v = AuditVerdict.Verdict.NO;
                default -> {
                    log.warn("无法识别的 verdict='{}', raw={}", verdictStr, raw);
                    v = AuditVerdict.Verdict.ERROR;
                }
            }

            return AuditVerdict.builder()
                    .verdict(v)
                    .confidence(Math.max(0.0, Math.min(1.0, conf)))
                    .reason(reason)
                    .model(model)
                    .build();
        } catch (Exception e) {
            log.warn("解析 LLM 输出失败 model={}, raw={}, err={}", model, raw, e.getMessage());
            return AuditVerdict.builder()
                    .verdict(AuditVerdict.Verdict.ERROR)
                    .confidence(0.0)
                    .reason("JSON 解析失败: " + e.getMessage())
                    .model(model)
                    .build();
        }
    }
}
