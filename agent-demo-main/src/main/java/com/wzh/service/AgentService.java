package com.wzh.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.entity.ChatSession;
import com.wzh.agentdemo.common.entity.FeatureDocument;
import com.wzh.agentdemo.common.entity.SysUser;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import com.wzh.agentdemo.common.mapper.ChatSessionMapper;
import com.wzh.agentdemo.common.mapper.SysUserMapper;
import com.wzh.common.UserContext;
import com.wzh.entity.FaqDocument;
import com.wzh.entity.dto.FaqDocumentDTO;
import com.wzh.entity.dto.FeatureDocumentDTO;
import com.wzh.service.MilvusService.ChunkData;
import com.wzh.service.MilvusService.SearchResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentService {

    private final FeatureDocumentService featureDocumentService;
    private final DashScopeService dashScopeService;
    private final MilvusService milvusService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final KnowledgeExtractService knowledgeExtractService;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;
    private final FaqDocumentService faqDocumentService;
    private final ChatClient mcpChatClient;

    public AgentService(FeatureDocumentService featureDocumentService,
                        DashScopeService dashScopeService,
                        MilvusService milvusService,
                        ImageUnderstandingService imageUnderstandingService,
                        KnowledgeExtractService knowledgeExtractService,
                        ChatSessionMapper chatSessionMapper,
                        ChatMessageMapper chatMessageMapper,
                        SysUserMapper sysUserMapper,
                        ObjectMapper objectMapper,
                        FaqDocumentService faqDocumentService,
                        ChatClient mcpChatClient) {
        this.featureDocumentService = featureDocumentService;
        this.dashScopeService = dashScopeService;
        this.milvusService = milvusService;
        this.imageUnderstandingService = imageUnderstandingService;
        this.knowledgeExtractService = knowledgeExtractService;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sysUserMapper = sysUserMapper;
        this.objectMapper = objectMapper;
        this.faqDocumentService = faqDocumentService;
        this.mcpChatClient = mcpChatClient;
    }

    // ==================== 文档学习 ====================

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
    }

    // ==================== FAQ 学习 ====================

    public void learnFaq(Long faqId) {
        FaqDocumentDTO faq = faqDocumentService.getFaqById(faqId);
        milvusService.deleteByDocId(-faqId);
        List<ChunkData> chunks = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        sb.append("知识类型: 用户常见问题(FAQ)\n");
        if (faq.getRelatedFeatureName() != null) sb.append("功能名称: ").append(faq.getRelatedFeatureName()).append("\n");
        sb.append("问题: ").append(faq.getQuestion()).append("\n");
        sb.append("答案: ").append(faq.getAnswer());

        List<String> allImages = new ArrayList<>();
        if (faq.getQuestionImages() != null) allImages.addAll(faq.getQuestionImages());
        if (faq.getAnswerImages() != null) allImages.addAll(faq.getAnswerImages());

        ChunkData chunk = new ChunkData();
        chunk.chunkId = IdUtil.fastSimpleUUID();
        chunk.docId = -faqId;
        chunk.chunkType = "faq_qa";
        chunk.featureName = faq.getRelatedFeatureName() != null ? faq.getRelatedFeatureName() : "通用FAQ";
        chunk.content = sb.toString();
        chunk.imageUrls = allImages;
        chunks.add(chunk);

        if (faq.getQuestionImages() != null && !faq.getQuestionImages().isEmpty()) {
            chunks.addAll(buildImageChunks(-faqId, chunk.featureName, "faq_qa", "问题截图", faq.getQuestionImages(), faq.getQuestion()));
        }
        if (faq.getAnswerImages() != null && !faq.getAnswerImages().isEmpty()) {
            chunks.addAll(buildImageChunks(-faqId, chunk.featureName, "faq_qa", "答案截图", faq.getAnswerImages(), faq.getAnswer()));
        }

        List<String> texts = chunks.stream().map(c -> c.content).collect(Collectors.toList());
        List<List<Float>> vectors = dashScopeService.getEmbeddings(texts);
        for (int i = 0; i < chunks.size(); i++) chunks.get(i).vector = vectors.get(i);

        milvusService.insertChunks(chunks);

        FaqDocument faqUpdate = new FaqDocument();
        faqUpdate.setId(faqId);
        faqUpdate.setVectorized(1);
        faqDocumentService.updateById(faqUpdate);

        log.info("FAQ [{}] 学习完成，共生成 {} 个知识块", faq.getQuestion(), chunks.size());
    }

    // ==================== 流式对话 ====================

    public SseEmitter chatStream(Long sessionId, String userMessage, List<String> imageUrls) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Long userId = UserContext.getUserId();
        // 在请求线程捕获 TokenInfo，传播到新线程（ThreadLocal 不跨线程）
        com.wzh.utils.TokenUtil.TokenInfo tokenInfo = UserContext.get();

        if (sessionId == null) {
            ChatSession session = new ChatSession();
            session.setUserId(userId);
            session.setTitle("新对话");
            chatSessionMapper.insert(session);
            sessionId = session.getId();
        }
        final Long finalSessionId = sessionId;

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(finalSessionId);
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        if (imageUrls != null && !imageUrls.isEmpty()) {
            try { userMsg.setUserImages(objectMapper.writeValueAsString(imageUrls)); } catch (Exception ignored) {}
        }
        chatMessageMapper.insert(userMsg);

        new Thread(() -> {
            UserContext.set(tokenInfo);
            try {
                // Step 1: 多模态图片理解
                String enhancedMessage = userMessage;
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    StringBuilder imageContext = new StringBuilder();
                    for (String imageUrl : imageUrls) {
                        try {
                            String desc = imageUnderstandingService.analyzeUserScreenshot(imageUrl, userMessage);
                            if (StrUtil.isNotBlank(desc)) imageContext.append("【用户截图内容】").append(desc).append("\n");
                        } catch (Exception e) { log.warn("用户截图理解失败: {}", e.getMessage()); }
                    }
                    if (!imageContext.isEmpty()) enhancedMessage = userMessage + "\n\n" + imageContext;
                }

                // Step 2: 向量检索
                List<Float> queryVector = dashScopeService.getEmbedding(enhancedMessage);
                List<SearchResult> searchResults = milvusService.search(queryVector, 8);
                List<SearchResult> processedResults = postProcessSearchResults(searchResults);

                StringBuilder context = new StringBuilder();
                List<String> relatedImages = new ArrayList<>();
                if (!processedResults.isEmpty()) {
                    context.append("以下是从知识库中检索到的相关信息：\n\n");
                    int idx = 0;
                    for (SearchResult sr : processedResults) {
                        if ("image_description".equals(sr.chunkType)) { collectImages(sr.imageUrls, relatedImages); continue; }
                        idx++;
                        context.append(String.format("【知识片段 %d】(来源: %s - %s, 相关度: %.2f)\n%s\n\n",
                                idx, sr.featureName, sr.chunkType, sr.score, sr.content));
                        collectImages(sr.imageUrls, relatedImages);
                    }
                }

                List<SourceInfo> sources = processedResults.stream()
                        .filter(sr -> !"image_description".equals(sr.chunkType))
                        .map(sr -> { SourceInfo s = new SourceInfo(); s.featureName = sr.featureName; s.chunkType = sr.chunkType; s.score = sr.score; return s; })
                        .collect(Collectors.toList());

                // Step 3: 发送 meta
                emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(Map.of(
                        "sessionId", finalSessionId, "relatedImages", relatedImages, "sources", sources))));

                // Step 4: 加载历史对话
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
                            .content(m.getContent()).build());
                }

                // Step 5: 获取当前用户信息
                SysUser currentUser = sysUserMapper.selectById(userId);
                String currentUserRole = currentUser != null ? currentUser.getRole() : "user";
                String currentUserName = currentUser != null ? currentUser.getNickname() : "用户";
                String currentUserId = currentUser != null ? currentUser.getUsername() : String.valueOf(userId);

                String systemPrompt = buildSystemPrompt(context.toString(), currentUserRole);
                final String finalEnhancedMessage = enhancedMessage;

                // Step 6: 通过 Spring AI ChatClient 统一处理（工具调用由 MCP Client 自动路由到 MCP Server）
                // 把用户/会话上下文通过 toolContext 透传到 MCP Server，submitTicket 等工具会从 McpMeta 自动取到
                streamWithChatClient(emitter, systemPrompt, chatHistory, finalEnhancedMessage,
                        finalSessionId, relatedImages, sources, historyMessages,
                        currentUserId, currentUserName, finalSessionId);

            } catch (Exception e) {
                log.error("Agent 对话异常", e);
                try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); emitter.complete(); }
                catch (Exception ex) { emitter.completeWithError(ex); }
            }
        }).start();

        return emitter;
    }

    // ==================== 重新生成 ====================

    public SseEmitter regenerateMessage(Long messageId) {
        ChatMessage assistantMsg = chatMessageMapper.selectById(messageId);
        if (assistantMsg == null || !"assistant".equals(assistantMsg.getRole())) {
            throw new RuntimeException("消息不存在或非 AI 回答");
        }
        Long sessionId = assistantMsg.getSessionId();

        List<ChatMessage> previousMessages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getRole, "user")
                        .lt(ChatMessage::getId, messageId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT 1"));
        if (previousMessages.isEmpty()) throw new RuntimeException("找不到对应的用户消息");

        ChatMessage userMsg = previousMessages.get(0);
        List<String> imageUrls = null;
        if (StrUtil.isNotBlank(userMsg.getUserImages())) {
            try {
                imageUrls = objectMapper.readValue(userMsg.getUserImages(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception ignored) {}
        }
        chatMessageMapper.deleteById(messageId);
        return chatStreamInternal(sessionId, userMsg.getContent(), imageUrls);
    }

    private SseEmitter chatStreamInternal(Long sessionId, String userMessage, List<String> imageUrls) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Long userId = UserContext.getUserId();
        com.wzh.utils.TokenUtil.TokenInfo tokenInfo = UserContext.get();
        final Long finalSessionId = sessionId;

        new Thread(() -> {
            UserContext.set(tokenInfo);
            try {
                String enhancedMessage = userMessage;
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    StringBuilder imageContext = new StringBuilder();
                    for (String imageUrl : imageUrls) {
                        try {
                            String desc = imageUnderstandingService.analyzeUserScreenshot(imageUrl, userMessage);
                            if (StrUtil.isNotBlank(desc)) imageContext.append("【用户截图内容】").append(desc).append("\n");
                        } catch (Exception e) { log.warn("用户截图理解失败: {}", e.getMessage()); }
                    }
                    if (!imageContext.isEmpty()) enhancedMessage = userMessage + "\n\n" + imageContext;
                }

                List<Float> queryVector = dashScopeService.getEmbedding(enhancedMessage);
                List<SearchResult> searchResults = milvusService.search(queryVector, 8);
                List<SearchResult> processedResults = postProcessSearchResults(searchResults);

                StringBuilder context = new StringBuilder();
                List<String> relatedImages = new ArrayList<>();
                if (!processedResults.isEmpty()) {
                    context.append("以下是从知识库中检索到的相关信息：\n\n");
                    int idx = 0;
                    for (SearchResult sr : processedResults) {
                        if ("image_description".equals(sr.chunkType)) { collectImages(sr.imageUrls, relatedImages); continue; }
                        idx++;
                        context.append(String.format("【知识片段 %d】(来源: %s - %s, 相关度: %.2f)\n%s\n\n",
                                idx, sr.featureName, sr.chunkType, sr.score, sr.content));
                        collectImages(sr.imageUrls, relatedImages);
                    }
                }

                List<SourceInfo> sources = processedResults.stream()
                        .filter(sr -> !"image_description".equals(sr.chunkType))
                        .map(sr -> { SourceInfo s = new SourceInfo(); s.featureName = sr.featureName; s.chunkType = sr.chunkType; s.score = sr.score; return s; })
                        .collect(Collectors.toList());

                emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(Map.of(
                        "sessionId", finalSessionId, "relatedImages", relatedImages, "sources", sources))));

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
                            .content(m.getContent()).build());
                }

                SysUser currentUser = sysUserMapper.selectById(userId);
                String currentUserRole = currentUser != null ? currentUser.getRole() : "user";
                String currentUserName = currentUser != null ? currentUser.getNickname() : "用户";
                String currentUserId = currentUser != null ? currentUser.getUsername() : String.valueOf(userId);

                String systemPrompt = buildSystemPrompt(context.toString(), currentUserRole);
                final String finalEnhancedMessage = enhancedMessage;

                streamWithChatClient(emitter, systemPrompt, chatHistory, finalEnhancedMessage,
                        finalSessionId, relatedImages, sources, historyMessages,
                        currentUserId, currentUserName, finalSessionId);

            } catch (Exception e) {
                log.error("Agent 对话异常", e);
                try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); emitter.complete(); }
                catch (Exception ex) { emitter.completeWithError(ex); }
            } finally {
                UserContext.clear();
            }
        }).start();

        return emitter;
    }

    // ==================== Spring AI ChatClient 流式对话（含 MCP 工具自动调用）====================

    /**
     * 用 Spring AI ChatClient 做流式对话。
     * 工具调用由 MCP Client 自动发现并路由到 MCP Server，对上层透明。
     * 取代原手写的 chatWithTools + executeToolCall + chatStreamWithToolResult 三段式流程。
     */
    private void streamWithChatClient(SseEmitter emitter, String systemPrompt,
                                      List<Message> chatHistory, String userMessage,
                                      Long sessionId, List<String> relatedImages,
                                      List<SourceInfo> sources,
                                      List<ChatMessage> historyMessages,
                                      String currentUserId, String currentUserName,
                                      Long currentSessionId) {
        try {
            // 把原 DashScope 的 Message 列表转成 Spring AI 的消息对象
            List<org.springframework.ai.chat.messages.Message> springMessages = new ArrayList<>();
            springMessages.add(new SystemMessage(systemPrompt));
            for (Message m : chatHistory) {
                if (Role.USER.getValue().equals(m.getRole())) {
                    springMessages.add(new UserMessage(m.getContent()));
                } else if (Role.ASSISTANT.getValue().equals(m.getRole())) {
                    springMessages.add(new AssistantMessage(m.getContent()));
                }
            }
            springMessages.add(new UserMessage(userMessage));

            Prompt prompt = new Prompt(springMessages);
            StringBuilder fullContent = new StringBuilder();

            // 流式调用；工具调用由 ChatClient + MCP ToolCallbackProvider 自动完成
            mcpChatClient.prompt(prompt)
                    .toolContext(Map.of(
                            "userId", currentUserId == null ? "unknown" : currentUserId,
                            "userName", currentUserName == null ? "未知用户" : currentUserName,
                            "sessionId", currentSessionId == null ? 0L : currentSessionId
                    ))
                    .stream()
                    .content()
                    .doOnNext(delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            fullContent.append(delta);
                            try {
                                emitter.send(SseEmitter.event().name("token").data(delta));
                            } catch (Exception e) {
                                log.warn("SSE 发送失败", e);
                            }
                        }
                    })
                    .doOnComplete(() -> saveAssistantMessageAndComplete(
                            emitter, sessionId, fullContent.toString(),
                            relatedImages, sources, historyMessages))
                    .doOnError(err -> handleStreamError(emitter,
                            err instanceof Exception ? (Exception) err : new RuntimeException(err)))
                    .blockLast();

        } catch (Exception e) {
            handleStreamError(emitter, e);
        }
    }

    // ==================== 反馈 ====================

    public void submitFeedback(Long messageId, Integer rating, String reason) {
        ChatMessage message = chatMessageMapper.selectById(messageId);
        if (message == null) throw new RuntimeException("消息不存在");
        message.setFeedbackRating(rating);
        message.setFeedbackReason(reason);
        message.setFeedbackTime(LocalDateTime.now());
        chatMessageMapper.updateById(message);
    }

    // ==================== 导出 ====================

    public String exportSessionAsMarkdown(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) throw new RuntimeException("会话不存在");

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreateTime));

        StringBuilder md = new StringBuilder();
        md.append("# ").append(session.getTitle()).append("\n\n");
        md.append("> 导出时间: ").append(LocalDateTime.now()).append("\n\n---\n\n");

        for (ChatMessage msg : messages) {
            md.append("user".equals(msg.getRole()) ? "### 👤 用户\n\n" : "### 🤖 AI 助手\n\n");
            md.append(msg.getContent()).append("\n\n");
            if (StrUtil.isNotBlank(msg.getUserImages())) {
                try {
                    List<String> imgs = objectMapper.readValue(msg.getUserImages(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    if (!imgs.isEmpty()) {
                        md.append("**用户上传的截图：**\n\n");
                        for (String img : imgs) md.append("![截图](").append(img).append(")\n\n");
                    }
                } catch (Exception ignored) {}
            }
            md.append("---\n\n");
        }
        return md.toString();
    }

    // ==================== System Prompt ====================

    private String buildSystemPrompt(String retrievedContext, String userRole) {
        boolean isAdmin = "admin".equals(userRole);

        String adminToolsSection = isAdmin ? """
                
                === 管理员专属工具 ===
                
                作为管理员，你还可以使用以下工具：
                
                【listDocumentStatus】— 查询所有文档/视频的学习状态
                触发时机：管理员询问"学习情况怎么样"、"哪些文档已学习/未学习"、"列出文档学习详情"、"知识库状态"等时调用。
                返回数据处理要求（必须严格执行）：
                - 工具返回 documents 数组和 videos 数组，必须将两个数组的每一条记录都逐条列出，不得只汇报数量
                - 文档列表格式：序号. 【文档ID: {id}】{featureName} — {status}
                - 视频列表格式：序号. 【视频ID: {id}】{name} — {status}（所属功能ID: {featureId}）
                - 最后输出汇总：共 N 篇文档（已学习 X 篇，未学习 Y 篇）；共 M 个视频（已学习 A 个，未学习 B 个）
                
                【triggerKnowledgeLearning】— 触发知识库学习
                触发时机：管理员说"学习所有未学习的文档"、"重新学习XX文档"、"触发学习任务"时调用。
                - scope 参数说明：
                  · "all_unlearned" = 学习所有未学习的内容
                  · "doc_{id}" = 学习指定 ID 的功能文档（需先用 listDocumentStatus 获取 ID）
                  · "video_{id}" = 学习指定 ID 的视频
                - 学习任务是异步的，触发后立即告知用户"已触发，正在后台执行"，不要让用户等待。
                
                【analyzeUsageStats】— 使用情况统计分析
                触发时机：管理员询问"本周使用情况"、"哪些用户问得最多"、"用户满意度如何"、"大屏数据"时调用。
                - timeRange 参数：this_week（本周）/ last_week（上周）/ this_month（本月）/ last_30_days（近30天）
                - 拿到统计数据后，用自然语言组织成一段分析报告，重点说明亮点和需要关注的问题。
                
                """ : "";

        String basePrompt = """
                你是一个专业的软件产品技术支持助手。你的唯一知识来源是下方提供的知识片段，你必须严格基于这些知识来回答用户的问题。
                
                === 工单工具使用规则 ===
                
                你拥有工单相关工具：submitTicket（提交工单）和 queryTicketStatus（查询工单状态）。
                
                【何时调用 submitTicket】
                满足以下任一条件时调用：
                1. 用户明确说出：「转人工」「转给技术人员」「提交工单」「人工处理」等
                2. 知识库完全没有相关信息，且用户的问题是具体的功能故障或异常
                调用前先向用户确认：「好的，我来帮您提交工单，请稍候。」
                调用成功后告知工单编号并提示可查询进度。
                
                【何时调用 queryTicketStatus】
                用户提到工单编号并询问进度时调用，例如：「我的 TK-xxx 处理得怎么样了？」
                
                【不要滥用工具】
                - 知识库有答案时，直接回答，不要提交工单
                - 不要在没有工单编号的情况下调用 queryTicketStatus
                
                """ + adminToolsSection + """
                === 回答规则 ===
                
                【规则一：忠于知识库】
                回答必须以知识片段中的信息为准，严禁根据通用知识自行推测、编造答案。
                
                【规则二：按问题类型回答】
                1) 报错/故障排查类 → 直接给出原因 + 解决方法
                2) 操作步骤类 → 精简编号步骤 + 前置条件
                3) 功能介绍类 → 2-3句话概括用途和核心能力
                
                【规则三：控制回答长度】
                回答控制在300字以内，最长不超过500字。
                
                【规则四：格式规范】
                使用 Markdown 格式，善用**加粗**、有序列表等。
                
                【规则五：信息不足时的处理】
                知识库没有相关信息时，主动询问用户是否需要提交工单：
                「关于这个问题，我目前的知识库暂未覆盖完整答案。需要我帮您提交工单，让技术人员进一步协助吗？」
                
                【规则六：引用来源】
                回答末尾标注：（参考：XX功能-XX板块）
                
                使用中文回答。
                
                """;

        if (StrUtil.isNotBlank(retrievedContext)) {
            return basePrompt + retrievedContext;
        } else {
            return basePrompt + "当前知识库中没有检索到相关信息。\n";
        }
    }

    // ==================== 私有辅助方法 ====================

    private void saveAssistantMessageAndComplete(SseEmitter emitter, Long sessionId, String fullContent,
                                                 List<String> relatedImages, List<SourceInfo> sources,
                                                 List<ChatMessage> historyMessages) {
        try {
            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setSessionId(sessionId);
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(fullContent);
            assistantMsg.setRelatedImages(objectMapper.writeValueAsString(relatedImages));
            assistantMsg.setSources(objectMapper.writeValueAsString(sources));
            chatMessageMapper.insert(assistantMsg);

            if (historyMessages.size() <= 1) {
                ChatSession titleUpdate = new ChatSession();
                titleUpdate.setId(sessionId);
                List<ChatMessage> all = chatMessageMapper.selectList(
                        new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId)
                                .eq(ChatMessage::getRole, "user").orderByAsc(ChatMessage::getCreateTime).last("LIMIT 1"));
                if (!all.isEmpty()) {
                    String raw = all.get(0).getContent();
                    titleUpdate.setTitle(raw.length() > 30 ? raw.substring(0, 30) + "..." : raw);
                    chatSessionMapper.updateById(titleUpdate);
                }
            }

            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete();
        } catch (Exception e) {
            log.error("完成处理失败", e);
            emitter.completeWithError(e);
        }
    }

    private void handleStreamError(SseEmitter emitter, Exception error) {
        log.error("大模型流式调用失败", error);
        try {
            emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
            emitter.complete();
        } catch (Exception ex) { emitter.completeWithError(ex); }
    }

    private List<SearchResult> postProcessSearchResults(List<SearchResult> rawResults) {
        if (rawResults == null || rawResults.isEmpty()) return new ArrayList<>();
        List<SearchResult> filtered = rawResults.stream().filter(sr -> sr.score >= 0.5f).collect(Collectors.toList());
        if (filtered.isEmpty()) filtered = rawResults.stream().filter(sr -> sr.score >= 0.3f).toList();
        if (filtered.isEmpty()) return new ArrayList<>();

        List<SearchResult> knowledgeResults = new ArrayList<>(), textResults = new ArrayList<>(), imageResults = new ArrayList<>();
        for (SearchResult sr : filtered) {
            if (isKnowledgeChunkType(sr.chunkType)) knowledgeResults.add(sr);
            else if ("image_description".equals(sr.chunkType)) imageResults.add(sr);
            else textResults.add(sr);
        }

        List<SearchResult> finalResults = new ArrayList<>(knowledgeResults);
        if (!knowledgeResults.isEmpty()) {
            Set<String> covered = knowledgeResults.stream().map(sr -> sr.featureName).collect(Collectors.toSet());
            Map<String, SearchResult> best = new LinkedHashMap<>();
            for (SearchResult sr : textResults) {
                if (covered.contains(sr.featureName)) best.merge(sr.featureName, sr, (a, b) -> b.score > a.score ? b : a);
                else finalResults.add(sr);
            }
            finalResults.addAll(best.values());
        } else {
            finalResults.addAll(textResults);
        }

        finalResults.sort((a, b) -> Float.compare(b.score, a.score));
        if (finalResults.size() > 4) finalResults = new ArrayList<>(finalResults.subList(0, 4));
        finalResults.addAll(imageResults);
        return finalResults;
    }

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
                        if (StrUtil.isNotBlank(detail.getTitle())) sb.append("[").append(detail.getTitle()).append("] ");
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

    private List<String> extractKeyPoints(String text) {
        List<String> keyPoints = new ArrayList<>();
        String[] sentences = text.split("[。！？\\n]+");
        String[] keywords = {"必须", "需要先", "要先", "前提", "之前", "注意", "提示", "错误", "警告", "弹出", "否则", "如果没有", "若没有", "不能", "无法", "设置", "选择", "配置", "确保", "才能", "才可以", "方可"};
        for (String sentence : sentences) {
            String t = sentence.trim();
            if (t.length() < 5) continue;
            for (String kw : keywords) {
                if (t.contains(kw)) { keyPoints.add(t.length() > 80 ? t.substring(0, 80) + "..." : t); break; }
            }
            if (keyPoints.size() >= 5) break;
        }
        return keyPoints;
    }

    private String buildCrossReference(String currentSection, Map<String, String> sectionSummaries) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sectionSummaries.entrySet()) {
            if (e.getKey().equals(currentSection)) continue;
            if (sb.isEmpty()) sb.append("\n\n--- 相关信息（来自同一功能的其他板块） ---\n");
            sb.append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

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
        return "error_solution".equals(chunkType) || "prerequisite".equals(chunkType)
                || "caution".equals(chunkType) || "dependency".equals(chunkType);
    }

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

    private void collectImages(Object imageUrlsObj, List<String> target) {
        if (imageUrlsObj == null) return;
        if (imageUrlsObj instanceof List) {
            @SuppressWarnings("unchecked") List<String> imgList = (List<String>) imageUrlsObj;
            for (String url : imgList) { String t = url.trim(); if (!t.isEmpty() && !target.contains(t)) target.add(t); }
        } else if (imageUrlsObj instanceof String str) {
            if (StrUtil.isBlank(str) || "[]".equals(str)) return;
            if (str.startsWith("[")) {
                try {
                    List<String> imgs = objectMapper.readValue(str, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    for (String url : imgs) { String t = url.trim(); if (!t.isEmpty() && !target.contains(t)) target.add(t); }
                    return;
                } catch (Exception ignored) {}
            }
            for (String url : str.split(",")) { String t = url.trim(); if (!t.isEmpty() && !target.contains(t)) target.add(t); }
        }
    }

    @Data
    public static class SourceInfo {
        public String featureName;
        public String chunkType;
        public float score;
    }
}