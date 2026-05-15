package com.wzh.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wzh.agentdemo.common.entity.FaqCandidate;
import com.wzh.agentdemo.common.mapper.FaqCandidateMapper;
import com.wzh.entity.FaqDocument;
import com.wzh.mapper.FaqDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * FAQ 候选审核服务(第五刀 Batch 4 - AgentDemo 侧)。
 *
 * <p>核心能力:
 * <ul>
 *   <li>候选列表分页查询</li>
 *   <li>拒绝候选(改 review_status=REJECTED + 填 reviewer_note)</li>
 *   <li><b>学习候选</b>:核心三步——INSERT faq_document → 向量学习 → 回写 candidate</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqCandidateService {

    private final FaqCandidateMapper faqCandidateMapper;
    private final FaqDocumentMapper faqDocumentMapper;
    private final FaqVectorizeService faqVectorizeService;

    /**
     * 候选分页列表(可按 reviewStatus 筛选)。
     */
    public Page<FaqCandidate> page(String reviewStatus, int pageNum, int pageSize) {
        Page<FaqCandidate> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<FaqCandidate> wrapper = new LambdaQueryWrapper<FaqCandidate>()
                .orderByDesc(FaqCandidate::getCreateTime);
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            wrapper.eq(FaqCandidate::getReviewStatus, reviewStatus);
        }
        return faqCandidateMapper.selectPage(page, wrapper);
    }

    public FaqCandidate getById(Long id) {
        return faqCandidateMapper.selectById(id);
    }

    /**
     * 拒绝候选。
     */
    public void reject(Long candidateId, Long reviewerId, String reviewerName, String reviewerNote) {
        FaqCandidate existing = faqCandidateMapper.selectById(candidateId);
        if (existing == null) throw new RuntimeException("候选不存在");
        if (!"PENDING".equals(existing.getReviewStatus())) {
            throw new RuntimeException("候选状态非 PENDING, 不能再次审核");
        }
        FaqCandidate patch = new FaqCandidate();
        patch.setId(candidateId);
        patch.setReviewStatus("REJECTED");
        patch.setReviewerId(reviewerId);
        patch.setReviewerName(reviewerName);
        patch.setReviewerNote(reviewerNote);
        patch.setReviewedTime(LocalDateTime.now());
        faqCandidateMapper.updateById(patch);
        log.info("[FaqCandidate] 拒绝候选 candidateId={} reviewer={}", candidateId, reviewerName);
    }

    /**
     * 学习候选(核心:三步全部成功才算通过)。
     * <ol>
     *   <li>INSERT faq_document(生成正式 FAQ.id)</li>
     *   <li>调 FaqVectorizeService.learnFaq() 向量化写入 faq_vectors</li>
     *   <li>回写 faq_candidate.promoted_faq_id + review_status=LEARNED + reviewer 信息</li>
     * </ol>
     * <p>事务保证 step 1+3 原子,step 2 失败由 FaqVectorizeService 自身重试/回滚;若 step 2 抛错,
     * 事务回滚整个动作,候选状态保持 PENDING,管理员可重试。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Long learn(Long candidateId, Long reviewerId, String reviewerName) {
        FaqCandidate existing = faqCandidateMapper.selectById(candidateId);
        if (existing == null) throw new RuntimeException("候选不存在");
        if (!"PENDING".equals(existing.getReviewStatus())) {
            throw new RuntimeException("候选状态非 PENDING, 不能再次审核");
        }

        // Step 1: INSERT faq_document
        FaqDocument doc = new FaqDocument();
        doc.setQuestion(existing.getQuestion());
        doc.setQuestionImages(existing.getQuestionImages());
        doc.setAnswer(existing.getAnswer());
        doc.setAnswerImages(existing.getAnswerImages());
        doc.setRelatedFeatureId(existing.getRelatedFeatureId());
        doc.setRelatedFeatureName(existing.getRelatedFeatureName());
        doc.setVectorized(0);
        faqDocumentMapper.insert(doc);
        log.info("[FaqCandidate] INSERT faq_document 成功 faqId={} question='{}'",
                doc.getId(), shorten(doc.getQuestion(), 40));

        // Step 2: 向量学习
        try {
            faqVectorizeService.learnFaq(doc.getId());
        } catch (Exception e) {
            log.error("[FaqCandidate] 向量学习失败 faqId={}, 事务将回滚", doc.getId(), e);
            throw new RuntimeException("向量学习失败:" + e.getMessage(), e);
        }

        // Step 3: 回写候选
        FaqCandidate patch = new FaqCandidate();
        patch.setId(candidateId);
        patch.setReviewStatus("LEARNED");
        patch.setReviewerId(reviewerId);
        patch.setReviewerName(reviewerName);
        patch.setReviewedTime(LocalDateTime.now());
        patch.setPromotedFaqId(doc.getId());
        faqCandidateMapper.updateById(patch);

        log.info("[FaqCandidate] 学习候选完成 candidateId={} → faqId={} reviewer={}",
                candidateId, doc.getId(), reviewerName);
        return doc.getId();
    }

    private String shorten(String s, int len) {
        if (s == null) return "";
        return s.length() <= len ? s : s.substring(0, len) + "...";
    }
}