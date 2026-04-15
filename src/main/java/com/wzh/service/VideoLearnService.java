package com.wzh.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.wzh.config.DashScopeConfig;
import com.wzh.entity.FeatureDocument;
import com.wzh.entity.VideoDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoLearnService {

    private final VideoDocumentService videoDocumentService;
    private final FeatureDocumentService featureDocumentService;
    private final DashScopeService dashScopeService;
    private final MilvusService milvusService;
    private final DashScopeConfig dashScopeConfig;

    //@Value("${dashscope.api-key}")
    //private String apiKey;

    /**
     * 异步学习指定功能文档关联的所有视频
     */
    @Async
    public void learnVideosAsync(Long featureId) {
        List<VideoDocument> videos = videoDocumentService.getByFeatureId(featureId);
        if (videos.isEmpty()) {
            log.warn("功能文档 [{}] 没有关联视频，跳过视频学习", featureId);
            return;
        }

        // 获取功能名称，用于 chunk 元数据
        FeatureDocument featureDoc = featureDocumentService.getById(featureId);
        String featureName = featureDoc != null ? featureDoc.getFeatureName() : "未知功能";

        log.info("开始学习功能文档 [{}] 的 {} 个视频", featureName, videos.size());

        for (VideoDocument video : videos) {
            try {
                // 更新状态为学习中
                video.setLearnStatus(1);
                videoDocumentService.updateById(video);

                // 执行单个视频的学习
                learnSingleVideo(video, featureId, featureName);

                // 更新状态为已学习
                video.setLearnStatus(2);
                videoDocumentService.updateById(video);

                log.info("视频 [{}] 学习完成", video.getOriginalName());
            } catch (Exception e) {
                log.error("视频 [{}] 学习失败: {}", video.getOriginalName(), e.getMessage(), e);
                video.setLearnStatus(3);
                videoDocumentService.updateById(video);
            }
        }

        log.info("功能文档 [{}] 的所有视频学习完成", featureName);
    }

    /**
     * 学习单个视频
     * 将视频URL直接发送给 qwen3.5-plus，利用其原生视频理解能力
     */
    private void learnSingleVideo(VideoDocument video, Long featureId, String featureName) {
        String videoUrl = video.getFileUrl();

        // 先删除该视频之前的旧 chunk（重新学习场景）
        milvusService.deleteByDocIdAndChunkType(featureId, "video_segment");

        List<MilvusService.ChunkData> chunks = new ArrayList<>();

        // 第一步：让模型对视频做整体摘要和操作步骤提取
        String videoAnalysis = analyzeVideo(videoUrl, featureName);
        if (StrUtil.isBlank(videoAnalysis)) {
            throw new RuntimeException("视频分析结果为空");
        }

        // 第二步：将分析结果按段落拆分成多个 chunk
        List<String> segments = splitAnalysisToSegments(videoAnalysis);

        for (int i = 0; i < segments.size(); i++) {
            String segmentContent = segments.get(i);
            if (StrUtil.isBlank(segmentContent)) continue;

            // 构建 chunk 内容，带上功能名称和来源标识
            String chunkContent = String.format(
                    "【功能：%s】【来源：教学视频 - %s】\n%s",
                    featureName, video.getOriginalName(), segmentContent.trim()
            );

            MilvusService.ChunkData chunk = new MilvusService.ChunkData();
            chunk.chunkId = IdUtil.fastSimpleUUID();
            chunk.docId = featureId;
            chunk.chunkType = "video_segment";
            chunk.featureName = featureName;
            chunk.content = chunkContent;
            chunk.imageUrls = new ArrayList<>(); // 视频 chunk 不关联图片
            chunks.add(chunk);
        }

        if (chunks.isEmpty()) {
            log.warn("视频 [{}] 未生成有效的知识块", video.getOriginalName());
            return;
        }

        // 第三步：批量 Embedding 并入库 Milvus
        List<String> texts = chunks.stream().map(c -> c.content).collect(Collectors.toList());
        List<List<Float>> vectors = dashScopeService.getEmbeddings(texts);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).vector = vectors.get(i);
        }

        milvusService.insertChunks(chunks);

        log.info("视频 [{}] 入库完成，共生成 {} 个知识块", video.getOriginalName(), chunks.size());
    }

    /**
     * 按视频 ID 学习单个视频（供 KnowledgeToolService 的 Agent 工具调用）
     * 同步执行，调用方自行决定是否异步包裹（KnowledgeToolService 用 CompletableFuture 包裹）
     */
    public void learnVideoById(Long videoId) {
        VideoDocument video = videoDocumentService.getById(videoId);
        if (video == null || video.getDeleted() == 1) {
            throw new RuntimeException("视频不存在或已删除 (id=" + videoId + ")");
        }

        // 获取关联功能名称
        Long featureId = video.getFeatureId();
        String featureName = "未知功能";
        if (featureId != null) {
            FeatureDocument featureDoc = featureDocumentService.getById(featureId);
            if (featureDoc != null) featureName = featureDoc.getFeatureName();
        }

        // 更新状态为学习中
        video.setLearnStatus(1);
        videoDocumentService.updateById(video);

        try {
            learnSingleVideo(video, featureId != null ? featureId : videoId, featureName);
            // 更新状态为已学习
            video.setLearnStatus(2);
            videoDocumentService.updateById(video);
            log.info("视频 [{}] (id={}) 学习完成", video.getOriginalName(), videoId);
        } catch (Exception e) {
            video.setLearnStatus(3);
            videoDocumentService.updateById(video);
            log.error("视频 [{}] (id={}) 学习失败: {}", video.getOriginalName(), videoId, e.getMessage());
            throw new RuntimeException("视频学习失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 qwen3.5-plus 分析视频内容
     * 利用其原生视频理解能力，直接传入视频 URL
     */
    private String analyzeVideo(String videoUrl, String featureName) {
        try {
            MultiModalConversation conv = new MultiModalConversation();

            // 构建系统提示词
            String systemPrompt = String.format(
                    "你是一个专业的软件操作教学视频分析助手。\n" +
                            "\n" +
                            "你的任务是分析一个关于「%s」功能的操作教学视频，并提取出完整、准确的操作流程。\n" +
                            "\n" +
                            "请严格根据视频中真实出现的内容进行分析，包括：\n" +
                            "- 软件界面操作\n" +
                            "- 屏幕字幕\n" +
                            "- 旁白讲解\n" +
                            "- 视频中的提示信息\n" +
                            "\n" +
                            "重要原则：\n" +
                            "1. 只允许描述视频中实际出现的操作和信息。\n" +
                            "2. 不要推测或扩展视频中没有展示的软件功能。\n" +
                            "3. 如果某些信息来自字幕或旁白，请结合这些信息进行理解。\n" +
                            "4. 操作步骤必须严格按照视频中的时间顺序。\n" +
                            "5. 如果某个操作只出现一次，也不要省略。\n" +
                            "\n" +
                            "请按照以下结构输出：\n" +
                            "\n" +
                            "## 视频概述\n" +
                            "用2-3句话总结视频演示的主要功能和操作目标。\n" +
                            "\n" +
                            "## 操作步骤\n" +
                            "按照视频时间顺序列出完整操作流程。\n" +
                            "\n" +
                            "每个步骤必须包含：\n" +
                            "- 操作动作：用户具体做了什么操作\n" +
                            "- 操作结果：该操作产生的界面变化或效果\n" +
                            "- 界面元素：视频中出现的按钮名称、窗口标题、字段名称等\n" +
                            "- 步骤总结：用一句话总结该步骤的核心目的\n" +
                            "\n" +
                            "要求：\n" +
                            "- 每一步只描述一个主要操作\n" +
                            "- 不要合并多个操作步骤\n" +
                            "- 尽量保留视频中的真实界面文本\n" +
                            "\n" +
                            "## 关键步骤\n" +
                            "从操作流程中找出最重要的步骤，并说明为什么这些步骤关键。\n" +
                            "\n" +
                            "## 关键知识点\n" +
                            "总结视频中讲解的核心操作知识，例如：\n" +
                            "- 功能作用\n" +
                            "- 参数设置\n" +
                            "- 系统行为\n" +
                            "\n" +
                            "## 常见错误与注意事项\n" +
                            "整理视频中提到的错误情况或需要特别注意的地方，包括：\n" +
                            "- 错误或问题现象\n" +
                            "- 出现原因\n" +
                            "- 解决方式或注意事项\n" +
                            "\n" +
                            "如果视频没有提到错误，请写：\n" +
                            "“视频中未展示相关错误或问题。”",
                    featureName
            );

            // 构建消息
            MultiModalMessage systemMessage = MultiModalMessage.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", systemPrompt)
                    ))
                    .build();

            MultiModalMessage userMessage = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(
                            Collections.singletonMap("video", videoUrl),
                            Collections.singletonMap("text",
                                    "请仔细观看该教学视频，结合画面、字幕和旁白内容，按时间顺序提取完整的操作步骤，并总结关键操作。")))
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getVideoModel())
                    .messages(Arrays.asList(systemMessage, userMessage))
                    .build();

            MultiModalConversationResult result = conv.call(param);

            String content = result.getOutput().getChoices().get(0)
                    .getMessage().getContent().get(0).get("text").toString();

            log.info("视频分析完成，输出长度: {} 字符", content.length());
            return content;

        } catch (Exception e) {
            log.error("调用 {} 分析视频失败: {}", dashScopeConfig.getVideoModel(), e.getMessage(), e);
            throw new RuntimeException("视频分析失败: " + e.getMessage());
        }
    }

    /**
     * 将视频分析结果按 ## 标题拆分成多个段落
     * 每个段落作为一个独立的 chunk 入库
     */
    private List<String> splitAnalysisToSegments(String analysis) {
        List<String> segments = new ArrayList<>();

        // 按 ## 标题分段
        String[] parts = analysis.split("(?=## )");
        for (String part : parts) {
            String trimmed = part.trim();
            if (StrUtil.isNotBlank(trimmed) && trimmed.length() > 20) {
                // 如果单个段落太长（超过1500字），再按换行分割
                if (trimmed.length() > 1500) {
                    List<String> subSegments = splitLongSegment(trimmed, 1200);
                    segments.addAll(subSegments);
                } else {
                    segments.add(trimmed);
                }
            }
        }

        // 如果分段失败（没有 ## 标题），按固定长度切分
        if (segments.isEmpty() && StrUtil.isNotBlank(analysis)) {
            segments = splitLongSegment(analysis, 1200);
        }

        return segments;
    }

    /**
     * 将过长的文本按段落边界切分
     */
    private List<String> splitLongSegment(String text, int maxLength) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\n\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (current.length() + para.length() > maxLength && current.length() > 0) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(para).append("\n\n");
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    /**
     * 测试用：直接分析视频并返回结果（同步，不入库）
     */
    public String analyzeVideoForTest(String videoUrl, String featureName) {
        return analyzeVideo(videoUrl, featureName);
    }

}