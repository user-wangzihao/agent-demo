package com.wzh.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.entity.FeatureDocument;
import com.wzh.entity.dto.FeatureDocumentDTO;
import com.wzh.entity.dto.PageRequest;
import com.wzh.mapper.FeatureDocumentMapper;
import com.wzh.service.FeatureDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureDocumentServiceImpl extends ServiceImpl<FeatureDocumentMapper, FeatureDocument>
        implements FeatureDocumentService {

    private final ObjectMapper objectMapper;

    @Override
    public Long addDocument(FeatureDocumentDTO dto) {
        FeatureDocument entity = convertToEntity(dto);
        this.save(entity);
        return entity.getId();
    }

    @Override
    public void updateDocument(FeatureDocumentDTO dto) {
        if (dto.getId() == null) {
            throw new RuntimeException("更新时ID不能为空");
        }
        FeatureDocument entity = convertToEntity(dto);
        entity.setId(dto.getId());
        this.updateById(entity);
    }

    @Override
    public FeatureDocumentDTO getDocumentById(Long id) {
        FeatureDocument entity = this.getById(id);
        if (entity == null) {
            throw new RuntimeException("文档不存在");
        }
        return convertToDTO(entity);
    }

    @Override
    public IPage<FeatureDocument> pageQuery(PageRequest request) {
        Page<FeatureDocument> page = new Page<>(request.getPageNum(), request.getPageSize());
        LambdaQueryWrapper<FeatureDocument> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(request.getKeyword())) {
            wrapper.like(FeatureDocument::getFeatureName, request.getKeyword());
        }
        wrapper.orderByDesc(FeatureDocument::getCreateTime);
        return this.page(page, wrapper);
    }

    @Override
    public void deleteDocument(Long id) {
        this.removeById(id);
    }

    // ========== 转换方法 ==========

    private FeatureDocument convertToEntity(FeatureDocumentDTO dto) {
        FeatureDocument entity = new FeatureDocument();
        entity.setFeatureName(dto.getFeatureName());
        entity.setEnglishName(dto.getEnglishName());
        entity.setAuthor(dto.getAuthor());
        entity.setVersion(dto.getVersion());
        entity.setPublishDate(dto.getPublishDate());
        entity.setCompany(dto.getCompany());
        try {
            if (dto.getFeatureIntro() != null) {
                entity.setFeatureIntro(objectMapper.writeValueAsString(dto.getFeatureIntro()));
            }
            if (dto.getFeatureDetails() != null) {
                entity.setFeatureDetails(objectMapper.writeValueAsString(dto.getFeatureDetails()));
            }
            if (dto.getOperationGuide() != null) {
                entity.setOperationGuide(objectMapper.writeValueAsString(dto.getOperationGuide()));
            }
            if (dto.getFaq() != null) {
                entity.setFaq(objectMapper.writeValueAsString(dto.getFaq()));
            }
        } catch (JsonProcessingException e) {
            log.error("JSON序列化失败", e);
            throw new RuntimeException("数据转换失败");
        }
        return entity;
    }

    private FeatureDocumentDTO convertToDTO(FeatureDocument entity) {
        FeatureDocumentDTO dto = new FeatureDocumentDTO();
        dto.setId(entity.getId());
        dto.setFeatureName(entity.getFeatureName());
        dto.setEnglishName(entity.getEnglishName());
        dto.setAuthor(entity.getAuthor());
        dto.setVersion(entity.getVersion());
        dto.setPublishDate(entity.getPublishDate());
        dto.setCompany(entity.getCompany());
        try {
            if (StrUtil.isNotBlank(entity.getFeatureIntro())) {
                dto.setFeatureIntro(objectMapper.readValue(entity.getFeatureIntro(),
                        FeatureDocumentDTO.SectionDTO.class));
            }
            if (StrUtil.isNotBlank(entity.getFeatureDetails())) {
                dto.setFeatureDetails(objectMapper.readValue(entity.getFeatureDetails(),
                        new TypeReference<List<FeatureDocumentDTO.FeatureDetailDTO>>() {}));
            }
            if (StrUtil.isNotBlank(entity.getOperationGuide())) {
                dto.setOperationGuide(objectMapper.readValue(entity.getOperationGuide(),
                        FeatureDocumentDTO.SectionDTO.class));
            }
            if (StrUtil.isNotBlank(entity.getFaq())) {
                dto.setFaq(objectMapper.readValue(entity.getFaq(),
                        FeatureDocumentDTO.SectionDTO.class));
            }
        } catch (JsonProcessingException e) {
            log.error("JSON反序列化失败", e);
            throw new RuntimeException("数据转换失败");
        }
        return dto;
    }
}