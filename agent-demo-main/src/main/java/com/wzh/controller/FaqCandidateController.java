package com.wzh.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wzh.agentdemo.common.entity.FaqCandidate;
import com.wzh.common.Result;
import com.wzh.common.UserContext;
import com.wzh.service.FaqCandidateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * FAQ 候选审核 API(第五刀 Batch 4 - 管理员侧)。
 *
 * <p>路径 /api/faq-candidate/**,走 AuthInterceptor 鉴权;
 * 全部接口要求管理员 role(在 service 层判)。</p>
 *
 * @author wzh
 * @since 2026-05-15
 */
@Slf4j
@RestController
@RequestMapping("/api/faq-candidate")
@RequiredArgsConstructor
public class FaqCandidateController {

    private final FaqCandidateService faqCandidateService;

    /**
     * 分页查询候选(可按 reviewStatus 筛选)。
     */
    @GetMapping("/page")
    public Result<Map<String, Object>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String reviewStatus) {
        requireAdmin();
        Page<FaqCandidate> p = faqCandidateService.page(reviewStatus, pageNum, pageSize);
        Map<String, Object> data = new HashMap<>();
        data.put("records", p.getRecords());
        data.put("total", p.getTotal());
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);
        return Result.success(data);
    }

    /**
     * 获取候选详情。
     */
    @GetMapping("/{id}")
    public Result<FaqCandidate> getById(@PathVariable Long id) {
        requireAdmin();
        FaqCandidate c = faqCandidateService.getById(id);
        if (c == null) return Result.error("候选不存在");
        return Result.success(c);
    }

    /**
     * 学习候选(三步:INSERT faq_document → 向量学习 → 回写)。
     */
    @PostMapping("/{id}/learn")
    public Result<Map<String, Object>> learn(@PathVariable Long id) {
        requireAdmin();
        Long userId = UserContext.getUserId();
        String userName = UserContext.get() != null ? UserContext.get().username : "admin";

        try {
            Long faqId = faqCandidateService.learn(id, userId, userName);
            Map<String, Object> data = new HashMap<>();
            data.put("candidateId", id);
            data.put("promotedFaqId", faqId);
            return Result.success(data);
        } catch (Exception e) {
            log.error("学习候选失败 candidateId={}", id, e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 拒绝候选(必填 reviewerNote)。
     */
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id,
                               @RequestBody Map<String, String> body) {
        requireAdmin();
        String note = body.get("reviewerNote");
        if (note == null || note.isBlank()) {
            return Result.error("拒绝时必须填写理由(reviewerNote)");
        }
        Long userId = UserContext.getUserId();
        String userName = UserContext.get() != null ? UserContext.get().username : "admin";

        try {
            faqCandidateService.reject(id, userId, userName, note);
            return Result.success(null);
        } catch (Exception e) {
            log.error("拒绝候选失败 candidateId={}", id, e);
            return Result.error(e.getMessage());
        }
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("权限不足:仅管理员可操作 FAQ 候选审核");
        }
    }
}