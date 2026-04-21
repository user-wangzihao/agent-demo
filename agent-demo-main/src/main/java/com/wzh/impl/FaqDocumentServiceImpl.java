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
        this.updateById(entity);
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
    }

    // ========== 转换 ==========

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