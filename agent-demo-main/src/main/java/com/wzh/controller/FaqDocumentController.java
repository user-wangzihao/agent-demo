package com.wzh.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wzh.common.Result;
import com.wzh.entity.FaqDocument;
import com.wzh.entity.dto.FaqDocumentDTO;
import com.wzh.entity.dto.PageRequest;
import com.wzh.service.AgentService;
import com.wzh.service.FaqDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/faq")
@RequiredArgsConstructor
public class FaqDocumentController {

    private final FaqDocumentService faqDocumentService;
    private final AgentService agentService;

    /** 新增 FAQ */
    @PostMapping
    public Result<Long> add(@RequestBody FaqDocumentDTO dto) {
        return Result.success(faqDocumentService.addFaq(dto));
    }

    /** 更新 FAQ */
    @PutMapping
    public Result<Void> update(@RequestBody FaqDocumentDTO dto) {
        faqDocumentService.updateFaq(dto);
        return Result.success();
    }

    /** 获取 FAQ 详情 */
    @GetMapping("/{id}")
    public Result<FaqDocumentDTO> getById(@PathVariable Long id) {
        return Result.success(faqDocumentService.getFaqById(id));
    }

    /** 分页查询 FAQ 列表 */
    @PostMapping("/page")
    public Result<IPage<FaqDocument>> page(@RequestBody PageRequest request) {
        return Result.success(faqDocumentService.pageQuery(request));
    }

    /** 删除 FAQ */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        faqDocumentService.deleteFaq(id);
        return Result.success();
    }

    /** 学习 FAQ（向量化） */
    @PostMapping("/{id}/learn")
    public Result<Void> learn(@PathVariable Long id) {
        agentService.learnFaq(id);
        return Result.success();
    }
}