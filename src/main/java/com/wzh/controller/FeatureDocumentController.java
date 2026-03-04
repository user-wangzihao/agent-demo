package com.wzh.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wzh.common.Result;
import com.wzh.entity.FeatureDocument;
import com.wzh.entity.dto.FeatureDocumentDTO;
import com.wzh.entity.dto.PageRequest;
import com.wzh.service.FeatureDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "功能文档管理", description = "功能文档增删改查")
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class FeatureDocumentController {

    private final FeatureDocumentService featureDocumentService;

    @Operation(summary = "新增功能文档")
    @PostMapping
    public Result<Long> add(@Valid @RequestBody FeatureDocumentDTO dto) {
        Long id = featureDocumentService.addDocument(dto);
        return Result.success(id);
    }

    @Operation(summary = "更新功能文档")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody FeatureDocumentDTO dto) {
        featureDocumentService.updateDocument(dto);
        return Result.success();
    }

    @Operation(summary = "根据ID查询功能文档")
    @GetMapping("/{id}")
    public Result<FeatureDocumentDTO> getById(@PathVariable Long id) {
        FeatureDocumentDTO dto = featureDocumentService.getDocumentById(id);
        return Result.success(dto);
    }

    @Operation(summary = "分页查询功能文档")
    @PostMapping("/page")
    public Result<IPage<FeatureDocument>> page(@RequestBody PageRequest request) {
        IPage<FeatureDocument> page = featureDocumentService.pageQuery(request);
        return Result.success(page);
    }

    @Operation(summary = "删除功能文档")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        featureDocumentService.deleteDocument(id);
        return Result.success();
    }
}