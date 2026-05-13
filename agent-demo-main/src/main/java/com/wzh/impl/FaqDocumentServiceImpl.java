package com.wzh.impl;

import com.azure.json.implementation.jackson.core.JsonProcessingException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xiaoymin.knife4j.core.util.StrUtil;
import com.wzh.entity.FaqDocument;
import com.wzh.entity.dto.FaqDocumentDTO;
import com.wzh.entity.dto.PageRequest;
import com.wzh.mapper.FaqDocumentMapper;
import com.wzh.service.FaqDocumentService;
import com.wzh.service.FaqMilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FaqDocumentServiceImpl extends ServiceImpl<FaqDocumentMapper, FaqDocument>
        implements FaqDocumentService {

    private final ObjectMapper objectMapper;
    /** 第四刀: 删除/更新 FAQ 时联动清理 faq_vectors */
    private final FaqMilvusService faqMilvusService;

    @Override
    public Long addFaq(FaqDocumentDTO dto) {
        FaqDocument entity = toEntity(dto);
        this.save(entity);
        return entity.getId();
    }

    @Override
    public void updateFaq(FaqDocumentDTO dto) {
        if (dto.getId() == null) throw new RuntimeException("更新时ID不能为空");
        FaqDocument entity = toEntity(dto);
        entity.setId(dto.getId());
        // 第四刀: 任一内容字段被改, 标记需要重新学习 (不自动重学, 保持可控)
        entity.setVectorized(0);
        this.updateById(entity);
        // 联动清理旧向量, 避免"老内容 + 老向量"被检索召回造成不一致
        // (vectorized=0 时 FAQ 不应该出现在检索结果里; 这里先清, 等用户手动点"学习")
        faqMilvusService.deleteByFaqId(dto.getId());
        log.info("FAQ [{}] 已更新, 旧向量已清除, vectorized 重置为 0", dto.getId());
    }

    @Override
    public FaqDocumentDTO getFaqById(Long id) {
        FaqDocument entity = this.getById(id);
        if (entity == null) throw new RuntimeException("FAQ不存在");
        return toDTO(entity);
    }

    @Override
    public IPage<FaqDocument> pageQuery(PageRequest request) {
        Page<FaqDocument> page = new Page<>(request.getPageNum(), request.getPageSize());
        LambdaQueryWrapper<FaqDocument> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(request.getKeyword())) {
            wrapper.like(FaqDocument::getQuestion, request.getKeyword());
        }
        wrapper.orderByDesc(FaqDocument::getCreateTime);
        return this.page(page, wrapper);
    }

    @Override
    public void deleteFaq(Long id) {
        this.removeById(id);
        // 第四刀: MySQL 软删后, 联动清 Milvus, 避免"鬼向量"被召回
        faqMilvusService.deleteByFaqId(id);
        log.info("FAQ [{}] 已软删除, 关联向量已清除", id);
    }

    // ========== 转换 (未改动) ==========

    private FaqDocument toEntity(FaqDocumentDTO dto) {
        FaqDocument entity = new FaqDocument();
        entity.setQuestion(dto.getQuestion());
        entity.setAnswer(dto.getAnswer());
        entity.setRelatedFeatureId(dto.getRelatedFeatureId());
        entity.setRelatedFeatureName(dto.getRelatedFeatureName());
        try {
            entity.setQuestionImages(dto.getQuestionImages() != null
                    ? objectMapper.writeValueAsString(dto.getQuestionImages()) : null);
            entity.setAnswerImages(dto.getAnswerImages() != null
                    ? objectMapper.writeValueAsString(dto.getAnswerImages()) : null);
        } catch (Exception e) {
            throw new RuntimeException("JSON序列化失败", e);
        }
        return entity;
    }

    private FaqDocumentDTO toDTO(FaqDocument entity) {
        FaqDocumentDTO dto = new FaqDocumentDTO();
        dto.setId(entity.getId());
        dto.setQuestion(entity.getQuestion());
        dto.setAnswer(entity.getAnswer());
        dto.setRelatedFeatureId(entity.getRelatedFeatureId());
        dto.setRelatedFeatureName(entity.getRelatedFeatureName());
        dto.setVectorized(entity.getVectorized());
        try {
            if (StrUtil.isNotBlank(entity.getQuestionImages())) {
                dto.setQuestionImages(objectMapper.readValue(entity.getQuestionImages(),
                        new TypeReference<List<String>>() {}));
            }
            if (StrUtil.isNotBlank(entity.getAnswerImages())) {
                dto.setAnswerImages(objectMapper.readValue(entity.getAnswerImages(),
                        new TypeReference<List<String>>() {}));
            }
        } catch (Exception e) {
            log.warn("JSON反序列化失败", e);
        }
        return dto;
    }
}