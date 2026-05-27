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
import com.wzh.service.SemanticCacheService;
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
    /** B4: 删除 FAQ 时联动失效缓存 (与你指定的 5 个失效点对齐: 重学/重新学习FAQ 走 FaqVectorizeService, 此处只管 delete) */
    private final SemanticCacheService semanticCacheService;

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
        // B4: 删除前先反查 relatedFeatureName, 用于事后失效缓存.
        // 必须在 removeById 之前读 — MyBatis-Plus 逻辑删除后仍能 selectById 但走的是 LogicDelete
        // 隐含 deleted=0 过滤; 为避免依赖逻辑删配置不一致, 提前读最稳.
        // null/blank 回退 "通用FAQ" 与 FaqVectorizeService.learnFaq 的 GENERAL_MARKER 对齐.
        FaqDocument old = this.getById(id);
        String featureName = (old != null && StrUtil.isNotBlank(old.getRelatedFeatureName()))
                ? old.getRelatedFeatureName() : "通用FAQ";

        this.removeById(id);
        // 第四刀: MySQL 软删后, 联动清 Milvus, 避免"鬼向量"被召回
        faqMilvusService.deleteByFaqId(id);
        log.info("FAQ [{}] 已软删除, 关联向量已清除", id);

        // B4: FAQ 内容已从知识库消失, 老缓存里基于该 FAQ 答案的回答现在是"鬼答案", 失效该 feature.
        // 注意失效粒度: invalidateByFeatureName 会清整个 feature 名下的所有缓存条目, 不是只清这条 FAQ
        // 相关的. 这是设计上的取舍 — cacheKey 的 hash 只绑 (featureName + intent + query),
        // 没办法精确知道哪条 cache 用了哪条 FAQ. feature 粒度失效是 demo 量级最干净的策略.
        semanticCacheService.invalidateByFeatureName(featureName);
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