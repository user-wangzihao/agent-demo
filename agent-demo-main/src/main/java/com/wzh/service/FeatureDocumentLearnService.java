package com.wzh.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.wzh.agentdemo.common.entity.FeatureDocument;
import com.wzh.entity.dto.FeatureDocumentDTO;
import com.wzh.service.MilvusService.ChunkData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 功能文档学习服务 (第六刀 Batch 4-1: 从 AgentService 拆出).
 *
 * <p><b>职责</b>: 把 FeatureDocument (文档型知识) 切分成 chunks, 调 embedding,
 * 写入 Milvus, 并把文档状态置为 vectorized=1.</p>
 *
 * <p><b>设计动机</b>: 之前 {@code learnDocument} 与主对话流程一起住在
 * {@code AgentService} 里, 但它和"对话"完全是两件事 (CRUD 与向量化 vs 检索与生成).
 * Batch 4 拆 AgentService, 把它独立成服务. 命名对齐 {@link VideoLearnService} ——
 * 文档/视频两类知识源的学习管道并列, 包内一眼看出对称的设计.</p>
 *
 * <p><b>chunk 构成</b>: 单篇文档 vectorize 后会产出:
 * <ul>
 *   <li>4 类主 chunk: feature_intro / feature_detail / operation_guide / faq</li>
 *   <li>每个主 chunk 对应若干 image_description 图片描述 chunk (走 ImageUnderstandingService)</li>
 *   <li>额外 4 类 knowledge chunk: error_solution / prerequisite / caution / dependency
 *       (走 KnowledgeExtractService 二次抽取)</li>
 * </ul>
 * 同 docId 重复学习时, 先 {@code deleteByDocId} 清空旧 chunks 再插入, 保证幂等.</p>
 *
 * <p><b>不在本服务职责内</b>:
 * <ul>
 *   <li>FAQ 学习 → {@link FaqVectorizeService} (独立 collection, 第四刀引入)</li>
 *   <li>视频学习 → {@link VideoLearnService}</li>
 *   <li>异步调度 → 调用方 (Controller / ToolService) 自己用 CompletableFuture 包装</li>
 * </ul></p>
 *
 * @author wzh
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureDocumentLearnService {

    private final FeatureDocumentService featureDocumentService;
    private final MilvusService milvusService;
    private final DashScopeService dashScopeService;
    private final KnowledgeExtractService knowledgeExtractService;
    private final ImageUnderstandingService imageUnderstandingService;
    /** B4: 学习完成后联动失效该 feature 的语义缓存. 失败容错由 SemanticCacheService 内部 try-catch 兜底. */
    private final SemanticCacheService semanticCacheService;

    // ==================== 公开入口 ====================

    /**
     * 学习单篇文档: 切分 chunks → 调 embedding → 写 Milvus → 标记 vectorized=1.
     *
     * <p><b>调用方</b>: AgentController (前端按钮) / InternalLearningController
     * (MCP Server 回调) / KnowledgeToolService (Agent 工具触发).</p>
     *
     * <p><b>同步执行</b>. 调用方按需用 {@code CompletableFuture.runAsync} 包装异步化.</p>
     *
     * @throws RuntimeException 文档内容为空时抛出
     */
    public void learnDocument(Long docId) {
        FeatureDocumentDTO doc = featureDocumentService.getDocumentById(docId);
        String featureName = doc.getFeatureName();
        milvusService.deleteByDocId(docId);
        Map<String, String> sectionSummaries = extractSectionSummaries(doc);
        List<ChunkData> chunks = new ArrayList<>();

        if (doc.getFeatureIntro() != null && StrUtil.isNotBlank(doc.getFeatureIntro().getDescription())) {
            String description = doc.getFeatureIntro().getDescription();
            String crossRef = buildCrossReference("feature_intro", sectionSummaries);
            chunks.add(buildChunk(docId, featureName, "feature_intro", null, description, doc.getFeatureIntro().getImages(), crossRef));
            chunks.addAll(buildImageChunks(docId, featureName, "feature_intro", null, doc.getFeatureIntro().getImages(), description));
        }
        if (doc.getFeatureDetails() != null) {
            for (int i = 0; i < doc.getFeatureDetails().size(); i++) {
                FeatureDocumentDTO.FeatureDetailDTO detail = doc.getFeatureDetails().get(i);
                if (StrUtil.isNotBlank(detail.getDescription())) {
                    String title = StrUtil.isNotBlank(detail.getTitle()) ? detail.getTitle() : "功能" + (i + 1);
                    String crossRef = buildCrossReference("feature_detail", sectionSummaries);
                    chunks.add(buildChunk(docId, featureName, "feature_detail", title, detail.getDescription(), detail.getImages(), crossRef));
                    chunks.addAll(buildImageChunks(docId, featureName, "feature_detail", title, detail.getImages(), detail.getDescription()));
                }
            }
        }
        if (doc.getOperationGuide() != null && StrUtil.isNotBlank(doc.getOperationGuide().getDescription())) {
            String description = doc.getOperationGuide().getDescription();
            String crossRef = buildCrossReference("operation_guide", sectionSummaries);
            chunks.add(buildChunk(docId, featureName, "operation_guide", null, description, doc.getOperationGuide().getImages(), crossRef));
            chunks.addAll(buildImageChunks(docId, featureName, "operation_guide", null, doc.getOperationGuide().getImages(), description));
        }
        if (doc.getFaq() != null && StrUtil.isNotBlank(doc.getFaq().getDescription())) {
            String description = doc.getFaq().getDescription();
            String crossRef = buildCrossReference("faq", sectionSummaries);
            chunks.add(buildChunk(docId, featureName, "faq", null, description, doc.getFaq().getImages(), crossRef));
            chunks.addAll(buildImageChunks(docId, featureName, "faq", null, doc.getFaq().getImages(), description));
        }
        if (chunks.isEmpty()) throw new RuntimeException("文档内容为空，无法学习");

        chunks.addAll(buildKnowledgeChunks(docId, featureName, doc));

        List<String> texts = chunks.stream().map(c -> c.content).collect(Collectors.toList());
        List<List<Float>> vectors = dashScopeService.getEmbeddings(texts);
        for (int i = 0; i < chunks.size(); i++) chunks.get(i).vector = vectors.get(i);

        milvusService.insertChunks(chunks);

        FeatureDocument update = new FeatureDocument();
        update.setId(docId);
        update.setVectorized(1);
        featureDocumentService.updateById(update);

        log.info("文档 [{}] 学习完成，共生成 {} 个知识块", featureName, chunks.size());

        // B4: 数据已变, 失效该 feature 名下所有缓存. 这一步即使失败也不影响学习结果落地,
        // SemanticCacheService.invalidateByFeatureName 内部已 try-catch.
        semanticCacheService.invalidateByFeatureName(featureName);
    }

    // ==================== 私有: section 摘要与交叉引用 ====================

    /**
     * 从 4 个 section (intro/details/guide/faq) 抽取摘要, 用于其他 chunk 拼"相关信息"块.
     *
     * <p><b>用意</b>: 单个 chunk 召回时不丢失其他 section 的上下文锚点 (轻量级 cross-reference).</p>
     */
    private Map<String, String> extractSectionSummaries(FeatureDocumentDTO doc) {
        Map<String, String> summaries = new LinkedHashMap<>();
        if (doc.getFeatureIntro() != null && StrUtil.isNotBlank(doc.getFeatureIntro().getDescription())) {
            String intro = doc.getFeatureIntro().getDescription().trim();
            summaries.put("feature_intro", "功能概述: " + (intro.length() > 100 ? intro.substring(0, 100) + "..." : intro));
        }
        if (doc.getFeatureDetails() != null) {
            StringBuilder sb = new StringBuilder();
            for (FeatureDocumentDTO.FeatureDetailDTO detail : doc.getFeatureDetails()) {
                if (StrUtil.isNotBlank(detail.getDescription())) {
                    List<String> kp = extractKeyPoints(detail.getDescription());
                    if (!kp.isEmpty()) {
                        if (StrUtil.isNotBlank(detail.getTitle()))
                            sb.append("[").append(detail.getTitle()).append("] ");
                        sb.append(String.join("; ", kp)).append(" ");
                    }
                }
            }
            if (!sb.isEmpty()) summaries.put("feature_detail", "功能要点: " + sb.toString().trim());
        }
        if (doc.getOperationGuide() != null && StrUtil.isNotBlank(doc.getOperationGuide().getDescription())) {
            List<String> kp = extractKeyPoints(doc.getOperationGuide().getDescription());
            summaries.put("operation_guide", kp.isEmpty()
                    ? "操作指南: " + (doc.getOperationGuide().getDescription().length() > 80 ? doc.getOperationGuide().getDescription().substring(0, 80) + "..." : doc.getOperationGuide().getDescription())
                    : "操作要点: " + String.join("; ", kp));
        }
        if (doc.getFaq() != null && StrUtil.isNotBlank(doc.getFaq().getDescription())) {
            List<String> kp = extractKeyPoints(doc.getFaq().getDescription());
            summaries.put("faq", kp.isEmpty()
                    ? "常见问题: " + (doc.getFaq().getDescription().length() > 80 ? doc.getFaq().getDescription().substring(0, 80) + "..." : doc.getFaq().getDescription())
                    : "常见问题: " + String.join("; ", kp));
        }
        return summaries;
    }

    /**
     * 按关键词列表从一段文字里挑出"关键句" (最多 5 句, 每句 ≤80 字).
     *
     * <p>关键词覆盖前置条件 / 操作约束 / 错误提示 / 注意事项几类语义.
     * 这是粗粒度规则匹配, 不追求 NLP 精度.</p>
     */
    private List<String> extractKeyPoints(String text) {
        List<String> keyPoints = new ArrayList<>();
        String[] sentences = text.split("[。！？\\n]+");
        String[] keywords = {"必须", "需要先", "要先", "前提", "之前", "注意", "提示", "错误", "警告", "弹出", "否则", "如果没有", "若没有", "不能", "无法", "设置", "选择", "配置", "确保", "才能", "才可以", "方可"};
        for (String sentence : sentences) {
            String t = sentence.trim();
            if (t.length() < 5) continue;
            for (String kw : keywords) {
                if (t.contains(kw)) {
                    keyPoints.add(t.length() > 80 ? t.substring(0, 80) + "..." : t);
                    break;
                }
            }
            if (keyPoints.size() >= 5) break;
        }
        return keyPoints;
    }

    /**
     * 拼当前 chunk 的"相关信息"块 (排除自己所属的 section).
     */
    private String buildCrossReference(String currentSection, Map<String, String> sectionSummaries) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sectionSummaries.entrySet()) {
            if (e.getKey().equals(currentSection)) continue;
            if (sb.isEmpty()) sb.append("\n\n--- 相关信息（来自同一功能的其他板块） ---\n");
            sb.append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 私有: chunk 构造器 ====================

    /**
     * 构造单个文本型 chunk.
     */
    private ChunkData buildChunk(Long docId, String featureName, String chunkType, String subTitle,
                                 String description, List<String> images, String crossReference) {
        StringBuilder sb = new StringBuilder();
        sb.append("功能名称: ").append(featureName).append("\n");
        sb.append("所属板块: ").append(chunkType).append("\n");
        if (StrUtil.isNotBlank(subTitle)) sb.append("子功能: ").append(subTitle).append("\n");
        sb.append("内容: ").append(description);
        if (StrUtil.isNotBlank(crossReference)) sb.append(crossReference);
        ChunkData chunk = new ChunkData();
        chunk.chunkId = IdUtil.fastSimpleUUID();
        chunk.docId = docId;
        chunk.chunkType = chunkType;
        chunk.featureName = featureName;
        chunk.content = sb.toString();
        chunk.imageUrls = images;
        return chunk;
    }

    /**
     * 把图片转成 image_description chunks. 走 ImageUnderstandingService (多模态视觉).
     * 单个图片产生一个 chunk, chunkType 固定 "image_description".
     */
    private List<ChunkData> buildImageChunks(Long docId, String featureName, String chunkType,
                                             String subTitle, List<String> imageUrls, String textContext) {
        List<ChunkData> imageChunks = new ArrayList<>();
        if (imageUrls == null || imageUrls.isEmpty()) return imageChunks;
        List<String> descriptions = imageUnderstandingService.analyzeImages(imageUrls, featureName, chunkType, textContext);
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

    // ==================== 私有: knowledge 二次抽取 ====================

    /**
     * 把整篇文档拼成一段长文本, 喂给 KnowledgeExtractService 做"知识点二次抽取":
     * 错误解决方案 / 前置条件 / 注意事项 / 依赖关系四类. 每类抽出来后单独成 chunk.
     *
     * <p><b>价值</b>: 这些 chunk 的 chunk_type 在 IntentBoostUtil 里有意图加权,
     * 是命中 troubleshoot/how_to 类问题的关键召回源.</p>
     */
    private List<ChunkData> buildKnowledgeChunks(Long docId, String featureName, FeatureDocumentDTO doc) {
        List<ChunkData> knowledgeChunks = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        fullText.append("功能名称：").append(featureName).append("\n\n");
        if (doc.getFeatureIntro() != null && StrUtil.isNotBlank(doc.getFeatureIntro().getDescription()))
            fullText.append("【功能简介】\n").append(doc.getFeatureIntro().getDescription()).append("\n\n");
        if (doc.getFeatureDetails() != null)
            for (FeatureDocumentDTO.FeatureDetailDTO d : doc.getFeatureDetails())
                if (StrUtil.isNotBlank(d.getDescription()))
                    fullText.append("【").append(StrUtil.isNotBlank(d.getTitle()) ? d.getTitle() : "功能描述").append("】\n").append(d.getDescription()).append("\n\n");
        if (doc.getOperationGuide() != null && StrUtil.isNotBlank(doc.getOperationGuide().getDescription()))
            fullText.append("【操作指南】\n").append(doc.getOperationGuide().getDescription()).append("\n\n");
        if (doc.getFaq() != null && StrUtil.isNotBlank(doc.getFaq().getDescription()))
            fullText.append("【常见问题】\n").append(doc.getFaq().getDescription()).append("\n\n");

        String docText = fullText.toString().trim();
        if (docText.length() < 50) return knowledgeChunks;

        List<KnowledgeExtractService.ExtractedKnowledge> list = knowledgeExtractService.extractKnowledge(featureName, docText);
        for (KnowledgeExtractService.ExtractedKnowledge k : list) {
            ChunkData chunk = new ChunkData();
            chunk.chunkId = IdUtil.fastSimpleUUID();
            chunk.docId = docId;
            chunk.chunkType = k.getType();
            chunk.featureName = featureName;
            chunk.content = "功能名称: " + featureName + "\n知识类型: " + getKnowledgeTypeLabel(k.getType()) + "\n内容:\n" + k.getContent();
            chunk.imageUrls = new ArrayList<>();
            knowledgeChunks.add(chunk);
        }
        return knowledgeChunks;
    }

    /**
     * knowledge chunk 类型码 → 中文标签 (供 chunk content 自描述).
     */
    private String getKnowledgeTypeLabel(String type) {
        return switch (type) {
            case "error_solution" -> "错误与解决方案";
            case "prerequisite" -> "前置条件";
            case "caution" -> "操作注意事项";
            case "dependency" -> "操作依赖关系";
            default -> "其他知识";
        };
    }
}