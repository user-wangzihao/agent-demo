package com.wzh.controller;

import com.wzh.agentdemo.common.entity.FaqCandidate;
import com.wzh.agentdemo.common.entity.FeatureDocument;
import com.wzh.agentdemo.common.mapper.FaqCandidateMapper;
import com.wzh.agentdemo.common.mapper.FeatureDocumentMapper;
import com.wzh.common.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 内部 FAQ 候选接收接口(第五刀 Batch 4)。
 *
 * <p>路径前缀 /internal/,绕过 AuthInterceptor;用 X-Internal-Api-Key 自校验。</p>
 *
 * <p><b>用途</b>:接收 TicketSystem 的 webhook,把候选记录落到 AgentDemo
 * 的 {@code faq_candidate} 表,review_status=PENDING,等待管理员审核学习。</p>
 *
 * @author wzh
 * @since 2026-05-15
 */
@Slf4j
@RestController
@RequestMapping("/internal/faq-candidate")
@RequiredArgsConstructor
public class InternalFaqCandidateController {

    private final FaqCandidateMapper faqCandidateMapper;
    private final FeatureDocumentMapper featureDocumentMapper;

    @Value("${internal.api-key}")
    private String expectedApiKey;

    /**
     * 接收 TicketSystem 提交的 FAQ 候选。
     *
     * <p>请求体字段:
     * <ul>
     *   <li>sourceTicketId / sourceTicketNo - 来源工单</li>
     *   <li>question / questionImages / answer / answerImages - FAQ 内容</li>
     *   <li>relatedFeatureName - 关联功能名,AgentDemo 会反查 feature_document.id 补到 relatedFeatureId</li>
     *   <li>submittedById / submittedByName - 提交技术员信息</li>
     * </ul></p>
     */
    @PostMapping("/submit")
    public ResponseEntity<?> submit(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!verifyApiKey(apiKey)) {
            log.warn("[internal/faq-candidate/submit] 鉴权失败");
            return ResponseEntity.status(401).body(Result.error("X-Internal-Api-Key 校验失败"));
        }

        try {
            // 防重:同一工单不能重复提交
            Long sourceTicketId = toLong(body.get("sourceTicketId"));
            if (sourceTicketId == null) {
                return ResponseEntity.badRequest().body(Result.error("sourceTicketId 不能为空"));
            }
            Long existing = faqCandidateMapper.selectCount(
                    new LambdaQueryWrapper<FaqCandidate>()
                            .eq(FaqCandidate::getSourceTicketId, sourceTicketId));
            if (existing != null && existing > 0) {
                log.warn("[internal/faq-candidate/submit] 工单已有候选记录, 跳过 ticketId={}", sourceTicketId);
                return ResponseEntity.status(409)
                        .body(Result.error("该工单已经有候选记录,不能重复提交"));
            }

            // 反查 feature_document.id
            String featureName = stringOrDefault(body.get("relatedFeatureName"), "通用FAQ");
            Long relatedFeatureId = resolveFeatureId(featureName);

            FaqCandidate c = new FaqCandidate();
            c.setSourceTicketId(sourceTicketId);
            c.setSourceTicketNo(stringOrDefault(body.get("sourceTicketNo"), ""));
            c.setSubmittedById(toLong(body.get("submittedById")));
            c.setSubmittedByName(stringOrDefault(body.get("submittedByName"), "未知"));
            c.setQuestion(stringOrDefault(body.get("question"), ""));
            c.setQuestionImages(stringOrDefault(body.get("questionImages"), null));
            c.setAnswer(stringOrDefault(body.get("answer"), ""));
            c.setAnswerImages(stringOrDefault(body.get("answerImages"), null));
            c.setRelatedFeatureId(relatedFeatureId);
            c.setRelatedFeatureName(featureName);
            c.setReviewStatus("PENDING");

            faqCandidateMapper.insert(c);

            log.info("[internal/faq-candidate/submit] 接收候选成功 candidateId={} ticketNo={} feature={}",
                    c.getId(), c.getSourceTicketNo(), featureName);

            return ResponseEntity.ok(Result.success(Map.of(
                    "candidateId", c.getId(),
                    "reviewStatus", c.getReviewStatus()
            )));
        } catch (Exception e) {
            log.error("[internal/faq-candidate/submit] 接收候选失败", e);
            return ResponseEntity.status(500).body(Result.error("接收失败:" + e.getMessage()));
        }
    }

    // ==================== 辅助 ====================

    /**
     * 根据 featureName 反查 feature_document.id。
     * 找不到返回 null(管理员审核时可在 AgentDemo 侧手动指定)。
     */
    private Long resolveFeatureId(String featureName) {
        if (featureName == null || featureName.isBlank() || "通用FAQ".equals(featureName)) {
            return null;
        }
        FeatureDocument doc = featureDocumentMapper.selectOne(
                new LambdaQueryWrapper<FeatureDocument>()
                        .eq(FeatureDocument::getFeatureName, featureName)
                        .last("LIMIT 1"));
        return doc == null ? null : doc.getId();
    }

    private boolean verifyApiKey(String apiKey) {
        return apiKey != null && apiKey.equals(expectedApiKey);
    }

    private String stringOrDefault(Object o, String defaultValue) {
        if (o == null) return defaultValue;
        String s = String.valueOf(o);
        return s.isBlank() ? defaultValue : s;
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); }
        catch (NumberFormatException e) { return null; }
    }
}