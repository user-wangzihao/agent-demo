package com.wzh.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.wzh.entity.FaqDocument;
import com.wzh.entity.dto.FaqDocumentDTO;
import com.wzh.service.MilvusService.ChunkData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FAQ 向量化服务 (第四刀引入).
 *
 * <p><b>背景</b>: AgentService.learnFaq() 既有实现把 FAQ chunk 用负 docId
 * 混存到主 collection (feature_document_vectors). 第四刀拆分后, FAQ 改写入
 * 独立的 faq_vectors collection, 用正 faqId 作 faq_id 字段值.</p>
 *
 * <p><b>为什么不改 AgentService 而是新建类</b>: 遵守"AgentService 在第六刀
 * 之前一行不动"的约束. AgentService.learnFaq() 与 buildImageChunks() 是
 * <b>整段复制</b>过来后改写入目标, 不是搬移. 第六刀 AgentService 下线时
 * 这两份代码一起消失, 短期重复不算债.</p>
 *
 * @author wzh
 * @since 2026-05-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqVectorizeService {

    private final FaqDocumentService faqDocumentService;
    private final DashScopeService dashScopeService;
    private final FaqMilvusService faqMilvusService;
    private final ImageUnderstandingService imageUnderstandingService;
    /** B4: 学习完成后联动失效该 feature 的语义缓存. */
    private final SemanticCacheService semanticCacheService;

    /** 通用 FAQ 的 feature_name 字面值 (沿用既定约定; 与 FaqRetrieveProperties.generalMarker 对齐) */
    private static final String GENERAL_MARKER = "通用FAQ";

    /**
     * 学习一条 FAQ: 生成 chunks (主 QA chunk + 图片描述 chunks) → embedding → 写入 faq_vectors.
     *
     * <p><b>幂等保证</b>: 先按 faqId 删旧, 再插新. 多次调用结果一致.</p>
     */
    public void learnFaq(Long faqId) {
        FaqDocumentDTO faq = faqDocumentService.getFaqById(faqId);

        // 先删旧 (按正整数 faqId, 不再用 -faqId)
        faqMilvusService.deleteByFaqId(faqId);

        List<ChunkData> chunks = new ArrayList<>();
        String featureName = cn.hutool.core.util.StrUtil.isNotBlank(faq.getRelatedFeatureName())
                ? faq.getRelatedFeatureName() : GENERAL_MARKER;

        // 1) 主 QA chunk
        StringBuilder sb = new StringBuilder();
        sb.append("知识类型: 用户常见问题(FAQ)\n");
        if (cn.hutool.core.util.StrUtil.isNotBlank(faq.getRelatedFeatureName())) {
            sb.append("功能名称: ").append(faq.getRelatedFeatureName()).append("\n");
        }
        sb.append("问题: ").append(faq.getQuestion()).append("\n");
        sb.append("答案: ").append(faq.getAnswer());

        List<String> allImages = new ArrayList<>();
        if (faq.getQuestionImages() != null) allImages.addAll(faq.getQuestionImages());
        if (faq.getAnswerImages() != null) allImages.addAll(faq.getAnswerImages());

        ChunkData qaChunk = new ChunkData();
        qaChunk.chunkId = IdUtil.fastSimpleUUID();
        qaChunk.docId = faqId;     // ← 正整数 faqId; FaqMilvusService 映射到 faq_id 字段
        qaChunk.chunkType = "faq_qa";
        qaChunk.featureName = featureName;
        qaChunk.content = sb.toString();
        qaChunk.imageUrls = allImages;
        chunks.add(qaChunk);

        // 2) 图片描述 chunks (question / answer 图片各一组)
        if (faq.getQuestionImages() != null && !faq.getQuestionImages().isEmpty()) {
            chunks.addAll(buildImageChunks(faqId, featureName, "faq_qa",
                    "问题截图", faq.getQuestionImages(), faq.getQuestion()));
        }
        if (faq.getAnswerImages() != null && !faq.getAnswerImages().isEmpty()) {
            chunks.addAll(buildImageChunks(faqId, featureName, "faq_qa",
                    "答案截图", faq.getAnswerImages(), faq.getAnswer()));
        }

        // 3) 批量 embedding (DashScope 内部 10 条一批, 顺序对齐)
        List<String> texts = chunks.stream().map(c -> c.content).collect(Collectors.toList());
        List<List<Float>> vectors = dashScopeService.getEmbeddings(texts);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).vector = vectors.get(i);
        }

        // 4) 写入 faq_vectors
        faqMilvusService.insertFaqChunks(chunks);

        // 5) 更新 MySQL vectorized 状态
        FaqDocument faqUpdate = new FaqDocument();
        faqUpdate.setId(faqId);
        faqUpdate.setVectorized(1);
        faqDocumentService.updateById(faqUpdate);

        log.info("FAQ [{}] 学习完成, 共生成 {} 个 chunk (写入 faq_vectors)",
                faq.getQuestion(), chunks.size());

        // B4: 数据已变, 失效该 feature 缓存. featureName 见前文 (relatedFeatureName 非空时即用,
        // 否则 = "通用FAQ"). 关键: invalidate 是按 feature 维度的, 通用 FAQ 在 demo 里集中失效
        // 是可接受的 (通用 FAQ 缓存条目本来就少).
        semanticCacheService.invalidateByFeatureName(featureName);
    }

    /**
     * 构造图片描述 chunks (从 AgentService 复制, 第六刀整段删除).
     *
     * <p><b>原始语义保留</b>: chunk_type 硬编码 "image_description";
     * 传入的 chunkType 参数仅用于拼 content 文本的"所属板块"字段, 不影响 schema 字段值.</p>
     *
     * @param docId        正整数 faqId
     * @param chunkType    传 "faq_qa" (用于 content 文本里的"所属板块"标识)
     * @param subTitle     "问题截图" / "答案截图"
     * @param textContext  关联文本 (question / answer 全文), 给图片理解服务做上下文
     */
    private List<ChunkData> buildImageChunks(Long docId, String featureName, String chunkType,
                                             String subTitle, List<String> imageUrls,
                                             String textContext) {
        List<ChunkData> imageChunks = new ArrayList<>();
        if (imageUrls == null || imageUrls.isEmpty()) return imageChunks;
        List<String> descriptions = imageUnderstandingService.analyzeImages(
                imageUrls, featureName, chunkType, textContext);
        for (int i = 0; i < descriptions.size(); i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("功能名称: ").append(featureName).append("\n");
            sb.append("所属板块: ").append(chunkType).append("\n");
            if (StrUtil.isNotBlank(subTitle)) sb.append("子功能: ").append(subTitle).append("\n");
            sb.append("内容类型: 界面截图描述\n图片描述: ").append(descriptions.get(i));
            ChunkData chunk = new ChunkData();
            chunk.chunkId = IdUtil.fastSimpleUUID();
            chunk.docId = docId;
            chunk.chunkType = "image_description";
            chunk.featureName = featureName;
            chunk.content = sb.toString();
            chunk.imageUrls = Collections.singletonList(imageUrls.get(i));
            imageChunks.add(chunk);
        }
        return imageChunks;
    }
}