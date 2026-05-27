package com.wzh.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.FeatureDocument;
import com.wzh.entity.dto.FeatureDocumentDTO;
import com.wzh.entity.dto.PageRequest;
import com.wzh.agentdemo.common.mapper.FeatureDocumentMapper;
import com.wzh.service.FeatureDocumentService;
import com.wzh.service.SemanticCacheService;
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
    /** B4: 删除文档时联动失效该 feature 缓存 */
    private final SemanticCacheService semanticCacheService;

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
        // B4: 删除前先反查 featureName, 用于事后失效缓存.
        // 必须在 removeById 之前读. featureName 为空回退到 null (不失效) — 文档没 featureName
        // 在业务上不应该出现, 但防御性处理: null 则跳过缓存失效, log warn.
        FeatureDocument old = this.getById(id);
        String featureName = (old != null) ? old.getFeatureName() : null;

        this.removeById(id);

        // B4: 文档物理删除 (不像 FAQ 是逻辑删除). 该 feature 的所有 doc-chunk 在 Milvus
        // 里实际上还残留 (这是 FeatureDocumentServiceImpl 的存量问题, 不在 B4 范围内修),
        // 但缓存失效是独立动作 — 不能等存量 bug 修了再补.
        if (StrUtil.isNotBlank(featureName)) {
            semanticCacheService.invalidateByFeatureName(featureName);
        } else {
            log.warn("[deleteDocument] id={} featureName 为空, 跳过缓存失效", id);
        }
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
            if (dto.getVideoUrls() != null){
                entity.setVideoUrls(objectMapper.writeValueAsString(dto.getVideoUrls()));
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