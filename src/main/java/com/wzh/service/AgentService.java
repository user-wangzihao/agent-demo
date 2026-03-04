package com.wzh.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wzh.common.UserContext;
import com.wzh.entity.FaqDocument;
import com.wzh.entity.dto.FaqDocumentDTO;
import com.wzh.entity.dto.FeatureDocumentDTO;
import com.wzh.entity.ChatMessage;
import com.wzh.entity.ChatSession;
import com.wzh.entity.FeatureDocument;
import com.wzh.mapper.ChatMessageMapper;
import com.wzh.mapper.ChatSessionMapper;
import com.wzh.service.MilvusService.ChunkData;
import com.wzh.service.MilvusService.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final FeatureDocumentService featureDocumentService;
    private final DashScopeService dashScopeService;
    private final MilvusService milvusService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final KnowledgeExtractService knowledgeExtractService;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;
    private final FaqDocumentService faqDocumentService;

    // ==================== 文档学习（方案一+二+三 综合改造） ====================

    /**
     * 将功能文档向量化存入 Milvus（"学习"功能）
     * <p>
     * 【方案一】：Chunk 内容增强 —— 每个 chunk 追加其他板块的关键摘要（交叉引用）
     * 【方案二】：关联 Chunk 构建 —— 调用大模型提取因果关系，生成 error_solution / prerequisite 等专用 chunk
     * 【方案三】：图片描述上下文增强 —— 把文字上下文传给 VL 模型，输出带因果关系的图片描述
     */
    public void learnDocument(Long docId) {
        FeatureDocumentDTO doc = featureDocumentService.getDocumentById(docId);
        String featureName = doc.getFeatureName();

        // 1. 先删除该文档的旧向量数据
        milvusService.deleteByDocId(docId);

        // 2. 【方案一新增】提取各板块的核心摘要，用于交叉引用
        Map<String, String> sectionSummaries = extractSectionSummaries(doc);

        List<ChunkData> chunks = new ArrayList<>();

        // 3. 按文档结构拆分文本 chunk + 图片描述 chunk

        // 3.1 功能简介
        if (doc.getFeatureIntro() != null && StrUtil.isNotBlank(doc.getFeatureIntro().getDescription())) {
            String description = doc.getFeatureIntro().getDescription();
            String crossRef = buildCrossReference("feature_intro", sectionSummaries);
            chunks.add(buildChunk(docId, featureName, "feature_intro", null,
                    description, doc.getFeatureIntro().getImages(), crossRef));
            chunks.addAll(buildImageChunks(docId, featureName, "feature_intro", null,
                    doc.getFeatureIntro().getImages(), description));
        }

        // 3.2 功能描述（多个）
        if (doc.getFeatureDetails() != null) {
            for (int i = 0; i < doc.getFeatureDetails().size(); i++) {
                FeatureDocumentDTO.FeatureDetailDTO detail = doc.getFeatureDetails().get(i);
                if (StrUtil.isNotBlank(detail.getDescription())) {
                    String title = StrUtil.isNotBlank(detail.getTitle()) ?
                            detail.getTitle() : "功能" + (i + 1);
                    String description = detail.getDescription();
                    String crossRef = buildCrossReference("feature_detail", sectionSummaries);
                    chunks.add(buildChunk(docId, featureName, "feature_detail", title,
                            description, detail.getImages(), crossRef));
                    chunks.addAll(buildImageChunks(docId, featureName, "feature_detail", title,
                            detail.getImages(), description));
                }
            }
        }

        // 3.3 操作指南
        if (doc.getOperationGuide() != null && StrUtil.isNotBlank(doc.getOperationGuide().getDescription())) {
            String description = doc.getOperationGuide().getDescription();
            String crossRef = buildCrossReference("operation_guide", sectionSummaries);
            chunks.add(buildChunk(docId, featureName, "operation_guide", null,
                    description, doc.getOperationGuide().getImages(), crossRef));
            chunks.addAll(buildImageChunks(docId, featureName, "operation_guide", null,
                    doc.getOperationGuide().getImages(), description));
        }

        // 3.4 常见问题
        if (doc.getFaq() != null && StrUtil.isNotBlank(doc.getFaq().getDescription())) {
            String description = doc.getFaq().getDescription();
            String crossRef = buildCrossReference("faq", sectionSummaries);
            chunks.add(buildChunk(docId, featureName, "faq", null,
                    description, doc.getFaq().getImages(), crossRef));
            chunks.addAll(buildImageChunks(docId, featureName, "faq", null,
                    doc.getFaq().getImages(), description));
        }

        if (chunks.isEmpty()) {
            throw new RuntimeException("文档内容为空，无法学习");
        }

        // 4. 【方案二】调用大模型提取结构化因果关系，构建关联 chunk
        chunks.addAll(buildKnowledgeChunks(docId, featureName, doc));

        // 5. 批量生成向量
        List<String> texts = chunks.stream().map(c -> c.content).collect(Collectors.toList());
        List<List<Float>> vectors = dashScopeService.getEmbeddings(texts);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).vector = vectors.get(i);
        }

        // 6. 写入 Milvus
        milvusService.insertChunks(chunks);

        // 7. 更新文档的向量化状态
        FeatureDocument update = new FeatureDocument();
        update.setId(docId);
        update.setVectorized(1);
        featureDocumentService.updateById(update);

        // 统计
        long textChunks = chunks.stream()
                .filter(c -> !"image_description".equals(c.chunkType) && !isKnowledgeChunkType(c.chunkType))
                .count();
        long imageChunks = chunks.stream()
                .filter(c -> "image_description".equals(c.chunkType))
                .count();
        long knowledgeChunks = chunks.stream()
                .filter(c -> isKnowledgeChunkType(c.chunkType))
                .count();
        log.info("文档 [{}] 学习完成，共生成 {} 个知识块（文本: {}, 图片描述: {}, 关联知识: {}）",
                featureName, chunks.size(), textChunks, imageChunks, knowledgeChunks);
    }

    // ==================== FAQ 学习 ====================

    /**
     * 学习单条 FAQ，构建高质量问答对 chunk 入库
     * chunk 格式：问题 + 答案 + 关联功能，自包含，检索精度高
     */
    public void learnFaq(Long faqId) {
        FaqDocumentDTO faq = faqDocumentService.getFaqById(faqId);

        // 删除旧向量数据
        milvusService.deleteByDocId(-faqId); // 用负数 docId 区分 FAQ 和功能文档

        List<ChunkData> chunks = new ArrayList<>();

        // 构建问答对 chunk
        StringBuilder sb = new StringBuilder();
        sb.append("知识类型: 用户常见问题(FAQ)\n");
        if (faq.getRelatedFeatureName() != null) {
            sb.append("功能名称: ").append(faq.getRelatedFeatureName()).append("\n");
        }
        sb.append("问题: ").append(faq.getQuestion()).append("\n");
        sb.append("答案: ").append(faq.getAnswer());

        // 收集所有相关图片（问题图片+答案图片）
        List<String> allImages = new ArrayList<>();
        if (faq.getQuestionImages() != null) allImages.addAll(faq.getQuestionImages());
        if (faq.getAnswerImages() != null) allImages.addAll(faq.getAnswerImages());

        ChunkData chunk = new ChunkData();
        chunk.chunkId = IdUtil.fastSimpleUUID();
        chunk.docId = -faqId; // 负数区分 FAQ
        chunk.chunkType = "faq_qa";
        chunk.featureName = faq.getRelatedFeatureName() != null ? faq.getRelatedFeatureName() : "通用FAQ";
        chunk.content = sb.toString();
        chunk.imageUrls = allImages;
        chunks.add(chunk);

        // 对问题图片做图片理解（如有），传入问题文字作为上下文
        if (faq.getQuestionImages() != null && !faq.getQuestionImages().isEmpty()) {
            chunks.addAll(buildImageChunks(-faqId,
                    chunk.featureName, "faq_qa", "问题截图", faq.getQuestionImages(),
                    faq.getQuestion()));
        }
        // 对答案图片做图片理解（如有），传入答案文字作为上下文
        if (faq.getAnswerImages() != null && !faq.getAnswerImages().isEmpty()) {
            chunks.addAll(buildImageChunks(-faqId,
                    chunk.featureName, "faq_qa", "答案截图", faq.getAnswerImages(),
                    faq.getAnswer()));
        }

        // 向量化并入库
        List<String> texts = chunks.stream().map(c -> c.content).collect(Collectors.toList());
        List<List<Float>> vectors = dashScopeService.getEmbeddings(texts);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).vector = vectors.get(i);
        }

        milvusService.insertChunks(chunks);

        // 更新向量化状态
        FaqDocument faqUpdate = new FaqDocument();
        faqUpdate.setId(faqId);
        faqUpdate.setVectorized(1);
        faqDocumentService.updateById(faqUpdate);

        log.info("FAQ [{}] 学习完成，共生成 {} 个知识块", faq.getQuestion(), chunks.size());
    }

    // ==================== 流式对话（SSE）— 支持用户上传图片 ====================

    /**
     * 流式 Agent 对话（支持多模态）
     * 1. 如果用户上传了图片，先用 qwen-vl-max 理解图片内容
     * 2. 将理解结果拼接到用户问题中，一起进行向量检索
     * 3. 检索相关知识 + 历史对话 + 流式大模型调用
     */
    public SseEmitter chatStream(Long sessionId, String userMessage, List<String> imageUrls) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Long userId = UserContext.getUserId();

        if (sessionId == null) {
            ChatSession session = new ChatSession();
            session.setUserId(userId);
            session.setTitle("新对话");
            chatSessionMapper.insert(session);
            sessionId = session.getId();
        }

        final Long finalSessionId = sessionId;

        // 保存用户消息（含上传图片信息）
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(finalSessionId);
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        if (imageUrls != null && !imageUrls.isEmpty()) {
            try {
                userMsg.setUserImages(objectMapper.writeValueAsString(imageUrls));
            } catch (Exception e) {
                log.warn("序列化用户图片失败", e);
            }
        }
        chatMessageMapper.insert(userMsg);

        new Thread(() -> {
            try {
                // ===== 1. 多模态图片理解（如果用户上传了截图） =====
                String enhancedMessage = userMessage;
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    log.info("用户上传了 {} 张截图，开始图片理解...", imageUrls.size());
                    StringBuilder imageContext = new StringBuilder();
                    for (String imageUrl : imageUrls) {
                        try {
                            String description = imageUnderstandingService.analyzeUserScreenshot(imageUrl, userMessage);
                            if (StrUtil.isNotBlank(description)) {
                                imageContext.append("【用户截图内容】").append(description).append("\n");
                            }
                        } catch (Exception e) {
                            log.warn("用户截图理解失败: {}", e.getMessage());
                        }
                    }
                    if (imageContext.length() > 0) {
                        enhancedMessage = userMessage + "\n\n" + imageContext.toString();
                        log.info("图片理解完成，增强后的问题长度: {}", enhancedMessage.length());
                    }
                }

                // ===== 2. 检索相关知识（topK=8，多检索再筛选） =====
                List<Float> queryVector = dashScopeService.getEmbedding(enhancedMessage);
                List<SearchResult> searchResults = milvusService.search(queryVector, 8);

                // 检索结果后处理：提高阈值、去重、排序
                List<SearchResult> processedResults = postProcessSearchResults(searchResults);

                StringBuilder context = new StringBuilder();
                List<String> relatedImages = new ArrayList<>();

                if (!processedResults.isEmpty()) {
                    context.append("以下是从知识库中检索到的相关信息：\n\n");
                    int contentIndex = 0;
                    for (int i = 0; i < processedResults.size(); i++) {
                        SearchResult sr = processedResults.get(i);
                        // 图片描述 chunk 只收集图片 URL，不把描述文本传给模型
                        if ("image_description".equals(sr.chunkType)) {
                            collectImages(sr.imageUrls, relatedImages);
                            continue;
                        }
                        contentIndex++;
                        context.append(String.format("【知识片段 %d】(来源: %s - %s, 相关度: %.2f)\n%s\n\n",
                                contentIndex, sr.featureName, sr.chunkType, sr.score, sr.content));
                        collectImages(sr.imageUrls, relatedImages);
                    }
                }

                // ===== 3. 发送元数据 =====
                List<SourceInfo> sources = processedResults.stream()
                        .filter(sr -> !"image_description".equals(sr.chunkType))
                        .map(sr -> {
                            SourceInfo s = new SourceInfo();
                            s.featureName = sr.featureName;
                            s.chunkType = sr.chunkType;
                            s.score = sr.score;
                            return s;
                        }).collect(Collectors.toList());

                String metaJson = objectMapper.writeValueAsString(Map.of(
                        "sessionId", finalSessionId,
                        "relatedImages", relatedImages,
                        "sources", sources
                ));
                emitter.send(SseEmitter.event().name("meta").data(metaJson));

                // ===== 4. 加载历史对话 =====
                List<ChatMessage> historyMessages = chatMessageMapper.selectList(
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getSessionId, finalSessionId)
                                .orderByAsc(ChatMessage::getCreateTime));

                int start = Math.max(0, historyMessages.size() - 21);
                List<Message> chatHistory = new ArrayList<>();
                for (int i = start; i < historyMessages.size() - 1; i++) {
                    ChatMessage m = historyMessages.get(i);
                    chatHistory.add(Message.builder()
                            .role("user".equals(m.getRole()) ? Role.USER.getValue() : Role.ASSISTANT.getValue())
                            .content(m.getContent())
                            .build());
                }

                // ===== 5. 流式调用大模型 =====
                String systemPrompt = buildSystemPrompt(context.toString());
                String finalUserMessage = enhancedMessage;

                dashScopeService.chatStream(systemPrompt, chatHistory, finalUserMessage,
                        // onToken
                        delta -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(delta));
                            } catch (Exception e) {
                                log.warn("SSE 发送失败", e);
                            }
                        },
                        // onComplete
                        fullContent -> {
                            try {
                                ChatMessage assistantMsg = new ChatMessage();
                                assistantMsg.setSessionId(finalSessionId);
                                assistantMsg.setRole("assistant");
                                assistantMsg.setContent(fullContent);
                                assistantMsg.setRelatedImages(objectMapper.writeValueAsString(relatedImages));
                                assistantMsg.setSources(objectMapper.writeValueAsString(sources));
                                chatMessageMapper.insert(assistantMsg);

                                if (historyMessages.size() <= 1) {
                                    ChatSession titleUpdate = new ChatSession();
                                    titleUpdate.setId(finalSessionId);
                                    String title = userMessage.length() > 30 ?
                                            userMessage.substring(0, 30) + "..." : userMessage;
                                    titleUpdate.setTitle(title);
                                    chatSessionMapper.updateById(titleUpdate);
                                }

                                emitter.send(SseEmitter.event().name("done").data(""));
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("完成处理失败", e);
                                emitter.completeWithError(e);
                            }
                        },
                        // onError
                        error -> {
                            log.error("大模型流式调用失败", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                                emitter.complete();
                            } catch (Exception ex) {
                                emitter.completeWithError(ex);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("Agent 对话异常", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        }).start();

        return emitter;
    }

    // ==================== 重新生成回答 ====================

    /**
     * 重新生成某条 assistant 消息的回答
     * 1. 删除该条 assistant 消息
     * 2. 找到它前面的那条 user 消息
     * 3. 用同样的 user 消息重新发起对话
     */
    public SseEmitter regenerateMessage(Long messageId) {
        ChatMessage assistantMsg = chatMessageMapper.selectById(messageId);
        if (assistantMsg == null || !"assistant".equals(assistantMsg.getRole())) {
            throw new RuntimeException("消息不存在或非 AI 回答");
        }

        Long sessionId = assistantMsg.getSessionId();

        // 找到该 assistant 消息之前的最近一条 user 消息
        List<ChatMessage> previousMessages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getRole, "user")
                        .lt(ChatMessage::getId, messageId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT 1"));

        if (previousMessages.isEmpty()) {
            throw new RuntimeException("找不到对应的用户消息");
        }

        ChatMessage userMsg = previousMessages.get(0);
        String userMessage = userMsg.getContent();

        // 解析用户图片
        List<String> imageUrls = null;
        if (StrUtil.isNotBlank(userMsg.getUserImages())) {
            try {
                imageUrls = objectMapper.readValue(userMsg.getUserImages(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                log.warn("解析用户图片失败", e);
            }
        }

        // 删除旧的 assistant 消息
        chatMessageMapper.deleteById(messageId);

        // 重新生成（不创建新的 user 消息，复用原有的）
        return chatStreamInternal(sessionId, userMessage, imageUrls, false);
    }

    /**
     * 内部流式对话方法（复用核心逻辑）
     * @param saveUserMessage 是否需要保存用户消息
     */
    private SseEmitter chatStreamInternal(Long sessionId, String userMessage, List<String> imageUrls, boolean saveUserMessage) {
        SseEmitter emitter = new SseEmitter(120_000L);

        if (saveUserMessage) {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(sessionId);
            userMsg.setRole("user");
            userMsg.setContent(userMessage);
            if (imageUrls != null && !imageUrls.isEmpty()) {
                try {
                    userMsg.setUserImages(objectMapper.writeValueAsString(imageUrls));
                } catch (Exception e) {
                    log.warn("序列化用户图片失败", e);
                }
            }
            chatMessageMapper.insert(userMsg);
        }

        final Long finalSessionId = sessionId;

        new Thread(() -> {
            try {
                // 多模态图片理解
                String enhancedMessage = userMessage;
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    StringBuilder imageContext = new StringBuilder();
                    for (String imageUrl : imageUrls) {
                        try {
                            String description = imageUnderstandingService.analyzeUserScreenshot(imageUrl, userMessage);
                            if (StrUtil.isNotBlank(description)) {
                                imageContext.append("【用户截图内容】").append(description).append("\n");
                            }
                        } catch (Exception e) {
                            log.warn("用户截图理解失败: {}", e.getMessage());
                        }
                    }
                    if (imageContext.length() > 0) {
                        enhancedMessage = userMessage + "\n\n" + imageContext.toString();
                    }
                }

                // 检索（topK=8 + 后处理）
                List<Float> queryVector = dashScopeService.getEmbedding(enhancedMessage);
                List<SearchResult> searchResults = milvusService.search(queryVector, 8);
                List<SearchResult> processedResults = postProcessSearchResults(searchResults);

                StringBuilder context = new StringBuilder();
                List<String> relatedImages = new ArrayList<>();

                if (!processedResults.isEmpty()) {
                    context.append("以下是从知识库中检索到的相关信息：\n\n");
                    int contentIndex = 0;
                    for (int i = 0; i < processedResults.size(); i++) {
                        SearchResult sr = processedResults.get(i);
                        if ("image_description".equals(sr.chunkType)) {
                            collectImages(sr.imageUrls, relatedImages);
                            continue;
                        }
                        contentIndex++;
                        context.append(String.format("【知识片段 %d】(来源: %s - %s, 相关度: %.2f)\n%s\n\n",
                                contentIndex, sr.featureName, sr.chunkType, sr.score, sr.content));
                        collectImages(sr.imageUrls, relatedImages);
                    }
                }

                List<SourceInfo> sources = processedResults.stream()
                        .filter(sr -> !"image_description".equals(sr.chunkType))
                        .map(sr -> {
                            SourceInfo s = new SourceInfo();
                            s.featureName = sr.featureName;
                            s.chunkType = sr.chunkType;
                            s.score = sr.score;
                            return s;
                        }).collect(Collectors.toList());

                String metaJson = objectMapper.writeValueAsString(Map.of(
                        "sessionId", finalSessionId,
                        "relatedImages", relatedImages,
                        "sources", sources
                ));
                emitter.send(SseEmitter.event().name("meta").data(metaJson));

                // 历史对话
                List<ChatMessage> historyMessages = chatMessageMapper.selectList(
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getSessionId, finalSessionId)
                                .orderByAsc(ChatMessage::getCreateTime));

                int start = Math.max(0, historyMessages.size() - 21);
                List<Message> chatHistory = new ArrayList<>();
                for (int i = start; i < historyMessages.size() - 1; i++) {
                    ChatMessage m = historyMessages.get(i);
                    chatHistory.add(Message.builder()
                            .role("user".equals(m.getRole()) ? Role.USER.getValue() : Role.ASSISTANT.getValue())
                            .content(m.getContent())
                            .build());
                }

                String systemPrompt = buildSystemPrompt(context.toString());

                dashScopeService.chatStream(systemPrompt, chatHistory, enhancedMessage,
                        // onToken
                        delta -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(delta));
                            } catch (Exception e) {
                                log.warn("SSE 发送失败", e);
                            }
                        },
                        // onComplete
                        fullContent -> {
                            try {
                                ChatMessage assistantMsg = new ChatMessage();
                                assistantMsg.setSessionId(finalSessionId);
                                assistantMsg.setRole("assistant");
                                assistantMsg.setContent(fullContent);
                                assistantMsg.setRelatedImages(objectMapper.writeValueAsString(relatedImages));
                                assistantMsg.setSources(objectMapper.writeValueAsString(sources));
                                chatMessageMapper.insert(assistantMsg);

                                emitter.send(SseEmitter.event().name("done").data(""));
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("完成处理失败", e);
                                emitter.completeWithError(e);
                            }
                        },
                        // onError
                        error -> {
                            log.error("大模型流式调用失败", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                                emitter.complete();
                            } catch (Exception ex) {
                                emitter.completeWithError(ex);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("Agent 对话异常", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        }).start();

        return emitter;
    }

    // ==================== 反馈 ====================

    public void submitFeedback(Long messageId, Integer rating, String reason) {
        ChatMessage message = chatMessageMapper.selectById(messageId);
        if (message == null) {
            throw new RuntimeException("消息不存在");
        }
        message.setFeedbackRating(rating);
        message.setFeedbackReason(reason);
        message.setFeedbackTime(LocalDateTime.now());
        chatMessageMapper.updateById(message);
        log.info("收到反馈: messageId={}, rating={}, reason={}", messageId, rating, reason);
    }

    // ==================== 对话导出 ====================

    /**
     * 导出会话为 Markdown 格式文本
     */
    public String exportSessionAsMarkdown(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreateTime));

        StringBuilder md = new StringBuilder();
        md.append("# ").append(session.getTitle()).append("\n\n");
        md.append("> 导出时间: ").append(LocalDateTime.now()).append("\n\n");
        md.append("---\n\n");

        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                md.append("### 👤 用户\n\n");
            } else {
                md.append("### 🤖 AI 助手\n\n");
            }
            md.append(msg.getContent()).append("\n\n");

            // 用户上传的图片
            if (StrUtil.isNotBlank(msg.getUserImages())) {
                try {
                    List<String> imgs = objectMapper.readValue(msg.getUserImages(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    if (!imgs.isEmpty()) {
                        md.append("**用户上传的截图：**\n\n");
                        for (String img : imgs) {
                            md.append("![截图](").append(img).append(")\n\n");
                        }
                    }
                } catch (Exception e) { /* ignore */ }
            }

            md.append("---\n\n");
        }

        return md.toString();
    }

    // ==================== 【方向二】检索结果后处理 ====================

    /**
     * 对 Milvus 检索结果做后处理：过滤、去重、排序、限制数量
     * <p>
     * 策略：
     * 1. 阈值过滤：相似度 < 0.5 的结果直接丢弃（降级到 0.3 兜底）
     * 2. 按类型分组：结构化知识 > 文本 > 图片描述
     * 3. 同功能去重：如果有结构化 chunk，同功能文本 chunk 只保留1个
     * 4. 图片描述 chunk 不作为主要知识来源，只收集图片 URL
     * 5. 最终限制最多传 4 个 chunk 给模型
     */
    private List<SearchResult> postProcessSearchResults(List<SearchResult> rawResults) {
        if (rawResults == null || rawResults.isEmpty()) {
            return new ArrayList<>();
        }

        // 第一步：阈值过滤
        List<SearchResult> filtered = rawResults.stream()
                .filter(sr -> sr.score >= 0.5f)
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            // 降级兜底
            filtered = rawResults.stream()
                    .filter(sr -> sr.score >= 0.3f)
                    .collect(Collectors.toList());
        }

        if (filtered.isEmpty()) {
            return new ArrayList<>();
        }

        // 第二步：按类型分组
        List<SearchResult> knowledgeResults = new ArrayList<>();
        List<SearchResult> textResults = new ArrayList<>();
        List<SearchResult> imageResults = new ArrayList<>();

        for (SearchResult sr : filtered) {
            if (isKnowledgeChunkType(sr.chunkType)) {
                knowledgeResults.add(sr);
            } else if ("image_description".equals(sr.chunkType)) {
                imageResults.add(sr);
            } else {
                textResults.add(sr);
            }
        }

        // 第三步：组装最终结果（优先级：结构化知识 > 文本 > 图片描述）
        List<SearchResult> finalResults = new ArrayList<>();

        // 结构化知识 chunk 全部保留
        finalResults.addAll(knowledgeResults);

        // 文本 chunk：如果已经有结构化知识覆盖同功能，只保留1个文本 chunk 做补充
        if (!knowledgeResults.isEmpty()) {
            Set<String> coveredFeatures = knowledgeResults.stream()
                    .map(sr -> sr.featureName)
                    .collect(Collectors.toSet());

            Map<String, SearchResult> bestTextPerFeature = new LinkedHashMap<>();
            for (SearchResult sr : textResults) {
                if (coveredFeatures.contains(sr.featureName)) {
                    bestTextPerFeature.merge(sr.featureName, sr,
                            (existing, newOne) -> newOne.score > existing.score ? newOne : existing);
                } else {
                    finalResults.add(sr);
                }
            }
            finalResults.addAll(bestTextPerFeature.values());
        } else {
            finalResults.addAll(textResults);
        }

        // 第四步：按相似度降序排列
        finalResults.sort((a, b) -> Float.compare(b.score, a.score));

        // 第五步：限制最多 4 个 chunk
        if (finalResults.size() > 4) {
            finalResults = new ArrayList<>(finalResults.subList(0, 4));
        }

        log.info("检索后处理: 原始 {} 条 → 过滤后 {} 条 → 最终 {} 条 (知识:{}, 文本:{}, 图片:{})",
                rawResults.size(), filtered.size(), finalResults.size(),
                knowledgeResults.size(), textResults.size(), imageResults.size());

        // 图片 chunk 追加到末尾（不传内容给模型，只收集图片 URL）
        finalResults.addAll(imageResults);

        return finalResults;
    }

    // ==================== 【方案一】板块摘要提取与交叉引用 ====================

    /**
     * 提取各板块的关键摘要信息，用于在其他板块的 chunk 中做交叉引用
     */
    private Map<String, String> extractSectionSummaries(FeatureDocumentDTO doc) {
        Map<String, String> summaries = new LinkedHashMap<>();
        String featureName = doc.getFeatureName();

        // 功能简介摘要
        if (doc.getFeatureIntro() != null && StrUtil.isNotBlank(doc.getFeatureIntro().getDescription())) {
            String intro = doc.getFeatureIntro().getDescription().trim();
            String summary = intro.length() > 100 ? intro.substring(0, 100) + "..." : intro;
            summaries.put("feature_intro", "功能概述: " + summary);
        }

        // 功能描述摘要 —— 重点提取前置条件、注意事项、错误提示
        if (doc.getFeatureDetails() != null) {
            StringBuilder detailSummary = new StringBuilder();
            for (FeatureDocumentDTO.FeatureDetailDTO detail : doc.getFeatureDetails()) {
                if (StrUtil.isNotBlank(detail.getDescription())) {
                    String text = detail.getDescription();
                    List<String> keyPoints = extractKeyPoints(text);
                    if (!keyPoints.isEmpty()) {
                        String title = StrUtil.isNotBlank(detail.getTitle()) ? detail.getTitle() : "";
                        if (StrUtil.isNotBlank(title)) {
                            detailSummary.append("[").append(title).append("] ");
                        }
                        detailSummary.append(String.join("; ", keyPoints)).append(" ");
                    }
                }
            }
            if (detailSummary.length() > 0) {
                summaries.put("feature_detail", "功能要点: " + detailSummary.toString().trim());
            }
        }

        // 操作指南摘要
        if (doc.getOperationGuide() != null && StrUtil.isNotBlank(doc.getOperationGuide().getDescription())) {
            String guide = doc.getOperationGuide().getDescription();
            List<String> keyPoints = extractKeyPoints(guide);
            if (!keyPoints.isEmpty()) {
                summaries.put("operation_guide", "操作要点: " + String.join("; ", keyPoints));
            } else {
                String summary = guide.length() > 80 ? guide.substring(0, 80) + "..." : guide;
                summaries.put("operation_guide", "操作指南: " + summary);
            }
        }

        // FAQ 摘要
        if (doc.getFaq() != null && StrUtil.isNotBlank(doc.getFaq().getDescription())) {
            String faq = doc.getFaq().getDescription();
            List<String> keyPoints = extractKeyPoints(faq);
            if (!keyPoints.isEmpty()) {
                summaries.put("faq", "常见问题: " + String.join("; ", keyPoints));
            } else {
                String summary = faq.length() > 80 ? faq.substring(0, 80) + "..." : faq;
                summaries.put("faq", "常见问题: " + summary);
            }
        }

        log.info("文档 [{}] 提取到 {} 个板块摘要", featureName, summaries.size());
        return summaries;
    }

    /**
     * 从文本中提取包含关键信息的句子
     */
    private List<String> extractKeyPoints(String text) {
        List<String> keyPoints = new ArrayList<>();

        String[] sentences = text.split("[。！？\\n]+");

        String[] keywords = {
                "必须", "需要先", "要先", "前提", "之前",
                "注意", "提示", "错误", "警告", "弹出",
                "否则", "如果没有", "若没有", "不能", "无法",
                "设置", "选择", "配置", "确保",
                "才能", "才可以", "方可"
        };

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.length() < 5) continue;

            for (String keyword : keywords) {
                if (trimmed.contains(keyword)) {
                    String point = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
                    keyPoints.add(point);
                    break;
                }
            }

            if (keyPoints.size() >= 5) break;
        }

        return keyPoints;
    }

    /**
     * 构建交叉引用文本
     */
    private String buildCrossReference(String currentSection, Map<String, String> sectionSummaries) {
        StringBuilder crossRef = new StringBuilder();

        for (Map.Entry<String, String> entry : sectionSummaries.entrySet()) {
            if (entry.getKey().equals(currentSection)) continue;

            if (crossRef.length() == 0) {
                crossRef.append("\n\n--- 相关信息（来自同一功能的其他板块） ---\n");
            }
            crossRef.append(entry.getValue()).append("\n");
        }

        return crossRef.toString();
    }

    // ==================== 【方案二】关联 Chunk 构建 ====================

    /**
     * 构建关联知识 chunk：调用大模型从文档中提取结构化因果关系
     */
    private List<ChunkData> buildKnowledgeChunks(Long docId, String featureName, FeatureDocumentDTO doc) {
        List<ChunkData> knowledgeChunks = new ArrayList<>();

        // 1. 拼接整个功能文档的所有板块文本
        StringBuilder fullText = new StringBuilder();
        fullText.append("功能名称：").append(featureName).append("\n\n");

        if (doc.getFeatureIntro() != null && StrUtil.isNotBlank(doc.getFeatureIntro().getDescription())) {
            fullText.append("【功能简介】\n").append(doc.getFeatureIntro().getDescription()).append("\n\n");
        }

        if (doc.getFeatureDetails() != null) {
            for (FeatureDocumentDTO.FeatureDetailDTO detail : doc.getFeatureDetails()) {
                if (StrUtil.isNotBlank(detail.getDescription())) {
                    String title = StrUtil.isNotBlank(detail.getTitle()) ? detail.getTitle() : "功能描述";
                    fullText.append("【").append(title).append("】\n").append(detail.getDescription()).append("\n\n");
                }
            }
        }

        if (doc.getOperationGuide() != null && StrUtil.isNotBlank(doc.getOperationGuide().getDescription())) {
            fullText.append("【操作指南】\n").append(doc.getOperationGuide().getDescription()).append("\n\n");
        }

        if (doc.getFaq() != null && StrUtil.isNotBlank(doc.getFaq().getDescription())) {
            fullText.append("【常见问题】\n").append(doc.getFaq().getDescription()).append("\n\n");
        }

        String docText = fullText.toString().trim();
        if (docText.length() < 50) {
            log.info("文档 [{}] 内容过短，跳过知识提取", featureName);
            return knowledgeChunks;
        }

        // 2. 调用大模型提取结构化知识
        log.info("开始对文档 [{}] 进行知识提取...", featureName);
        List<KnowledgeExtractService.ExtractedKnowledge> knowledgeList =
                knowledgeExtractService.extractKnowledge(featureName, docText);

        if (knowledgeList.isEmpty()) {
            log.info("文档 [{}] 未提取到结构化知识", featureName);
            return knowledgeChunks;
        }

        // 3. 每条提取结果构建为一个独立的 chunk
        for (KnowledgeExtractService.ExtractedKnowledge knowledge : knowledgeList) {
            StringBuilder sb = new StringBuilder();
            sb.append("功能名称: ").append(featureName).append("\n");
            sb.append("知识类型: ").append(getKnowledgeTypeLabel(knowledge.getType())).append("\n");
            sb.append("内容:\n").append(knowledge.getContent());

            ChunkData chunk = new ChunkData();
            chunk.chunkId = IdUtil.fastSimpleUUID();
            chunk.docId = docId;
            chunk.chunkType = knowledge.getType();
            chunk.featureName = featureName;
            chunk.content = sb.toString();
            chunk.imageUrls = new ArrayList<>();

            knowledgeChunks.add(chunk);
        }

        log.info("文档 [{}] 构建了 {} 个关联知识 chunk", featureName, knowledgeChunks.size());
        return knowledgeChunks;
    }

    private String getKnowledgeTypeLabel(String type) {
        return switch (type) {
            case "error_solution" -> "错误与解决方案";
            case "prerequisite" -> "前置条件";
            case "caution" -> "操作注意事项";
            case "dependency" -> "操作依赖关系";
            default -> "其他知识";
        };
    }

    private boolean isKnowledgeChunkType(String chunkType) {
        return "error_solution".equals(chunkType)
                || "prerequisite".equals(chunkType)
                || "caution".equals(chunkType)
                || "dependency".equals(chunkType);
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建文本 chunk
     * 【方案一改造】新增 crossReference 参数，追加到 chunk 内容末尾
     */
    private ChunkData buildChunk(Long docId, String featureName, String chunkType, String subTitle,
                                 String description, List<String> images, String crossReference) {
        StringBuilder sb = new StringBuilder();
        sb.append("功能名称: ").append(featureName).append("\n");
        sb.append("所属板块: ").append(chunkType).append("\n");
        if (StrUtil.isNotBlank(subTitle)) {
            sb.append("子功能: ").append(subTitle).append("\n");
        }
        sb.append("内容: ").append(description);

        // 【方案一】追加交叉引用信息
        if (StrUtil.isNotBlank(crossReference)) {
            sb.append(crossReference);
        }

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
     * 构建图片描述 chunk 列表
     * 【方案三改造】新增 textContext 参数，将文字上下文传给 VL 模型
     */
    private List<ChunkData> buildImageChunks(Long docId, String featureName, String chunkType,
                                             String subTitle, List<String> imageUrls,
                                             String textContext) {
        List<ChunkData> imageChunks = new ArrayList<>();

        if (imageUrls == null || imageUrls.isEmpty()) {
            return imageChunks;
        }

        // 【方案三】调用多模态模型批量分析图片，传入文字上下文
        List<String> descriptions = imageUnderstandingService.analyzeImages(
                imageUrls, featureName, chunkType, textContext);

        for (int i = 0; i < descriptions.size(); i++) {
            String description = descriptions.get(i);
            String imageUrl = imageUrls.get(i);

            StringBuilder sb = new StringBuilder();
            sb.append("功能名称: ").append(featureName).append("\n");
            sb.append("所属板块: ").append(chunkType).append("\n");
            if (StrUtil.isNotBlank(subTitle)) {
                sb.append("子功能: ").append(subTitle).append("\n");
            }
            sb.append("内容类型: 界面截图描述\n");
            sb.append("图片描述: ").append(description);

            ChunkData chunk = new ChunkData();
            chunk.chunkId = IdUtil.fastSimpleUUID();
            chunk.docId = docId;
            chunk.chunkType = "image_description";
            chunk.featureName = featureName;
            chunk.content = sb.toString();
            chunk.imageUrls = Collections.singletonList(imageUrl);

            imageChunks.add(chunk);
        }

        return imageChunks;
    }

    /**
     * 从 imageUrls 字段收集图片 URL 到目标列表
     * 注意：SearchResult.imageUrls 是 String 类型（JSON 格式），需要解析
     * 而 ChunkData.imageUrls 是 List<String> 类型
     * 此方法兼容两种情况
     */
    private void collectImages(Object imageUrlsObj, List<String> target) {
        if (imageUrlsObj == null) return;

        if (imageUrlsObj instanceof List) {
            // ChunkData.imageUrls 是 List<String>
            @SuppressWarnings("unchecked")
            List<String> imgList = (List<String>) imageUrlsObj;
            for (String url : imgList) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty() && !target.contains(trimmed)) {
                    target.add(trimmed);
                }
            }
        } else if (imageUrlsObj instanceof String) {
            // SearchResult.imageUrls 是 String（可能是 JSON 数组或逗号分隔）
            String str = (String) imageUrlsObj;
            if (StrUtil.isBlank(str) || "[]".equals(str)) return;

            // 尝试作为 JSON 数组解析
            if (str.startsWith("[")) {
                try {
                    List<String> imgs = objectMapper.readValue(str,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    for (String url : imgs) {
                        String trimmed = url.trim();
                        if (!trimmed.isEmpty() && !target.contains(trimmed)) {
                            target.add(trimmed);
                        }
                    }
                    return;
                } catch (Exception e) {
                    log.warn("解析图片URL JSON失败", e);
                }
            }

            // 降级：按逗号分隔处理
            for (String url : str.split(",")) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty() && !target.contains(trimmed)) {
                    target.add(trimmed);
                }
            }
        }
    }

    /**
     * 构建系统提示词
     * 按问题类型给出不同的回答模板，严格控制回答长度，禁止自由发挥
     */
    private String buildSystemPrompt(String retrievedContext) {
        String basePrompt = """
                你是一个专业的软件产品技术支持助手。你的唯一知识来源是下方提供的知识片段，你必须严格基于这些知识来回答用户的问题。
                
                === 回答规则（必须严格遵守） ===
                
                【规则一：忠于知识库】
                - 回答必须以知识片段中的信息为准，用你自己的语言组织，但核心信息不能偏离知识内容
                - 严禁根据通用知识自行推测、编造答案
                - 如果知识片段中包含明确的解决方案，直接给出，不要绕弯子
                
                【规则二：按问题类型回答】
                判断用户问题属于哪种类型，使用对应的回答方式：
                
                1) 报错/故障排查类（用户描述了错误提示、异常现象）：
                   → 直接回答：原因是什么 + 解决方法是什么
                   → 不需要分析过程，不需要列举多种可能性，直接给答案
                   → 示例格式："这个错误是因为XXX。解决方法：XXX。"
                
                2) 操作步骤类（用户问某个功能怎么用、怎么操作）：
                   → 给出精简的操作步骤，每步一句话
                   → 如果有前置条件，在步骤之前简要说明
                   → 示例格式："操作步骤：1. XXX  2. XXX  3. XXX"
                
                3) 功能介绍类（用户问某个功能是什么、有什么用）：
                   → 用2-3句话概括功能的用途和核心能力
                   → 不需要列举所有细节
                
                4) 其他类型：
                   → 基于知识片段简洁回答，抓住核心要点
                
                【规则三：控制回答长度】
                - 回答尽量控制在300字以内，最长不超过500字
                - 如果用户没有要求详细说明，默认给简洁版本
                - 宁可简短精准，不要冗长啰嗦
                - 不要重复相同的信息，不要用多种方式表达同一个意思
                
                【规则四：格式规范】
                - 回答时使用 Markdown 格式，善用**加粗**、有序列表、代码块等排版
                - 当提到操作步骤时，使用编号列表清晰呈现
                - 如果回答中涉及到某个界面或操作场景，可以用 [IMG:图片描述] 的格式标记应该展示截图的位置
                
                【规则五：信息不足时的处理】
                - 如果知识片段不能完全回答用户的问题，先回答能回答的部分
                - 然后明确告知："关于XXX部分，目前知识库暂未覆盖，建议联系技术支持进一步协助。"
                - 绝对不要用通用知识填补空白
                
                【规则六：引用来源】
                - 在回答末尾简要标注信息来源，格式："（参考：XX功能-XX板块）"
                - 不需要引用每一个片段，只引用最关键的来源
                
                使用中文回答。
                
                """;

        if (StrUtil.isNotBlank(retrievedContext)) {
            return basePrompt + retrievedContext;
        } else {
            return basePrompt + "当前知识库中没有检索到相关信息。请回复用户：\"抱歉，关于您的问题，我目前的知识库中暂未收录相关信息。建议您联系技术支持获取帮助。\"\n";
        }
    }

    // ==================== 数据类 ====================

    @Data
    public static class SourceInfo {
        public String featureName;
        public String chunkType;
        public float score;
    }
}
