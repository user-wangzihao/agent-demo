package com.wzh.controller;

import com.wzh.agentdemo.common.entity.VideoDocument;
import com.wzh.common.Result;
import com.wzh.entity.dto.FeedbackRequest;
import com.wzh.service.FeatureDocumentLearnService;
import com.wzh.service.FeedbackService;
import com.wzh.service.SessionExportService;
import com.wzh.service.VideoDocumentService;
import com.wzh.service.VideoLearnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Agent 端点 Controller (第六刀 Batch 5-a: 替代下线的 AgentController).
 *
 * <p><b>由来</b>: AgentController 之前同时承载"老 AgentService 业务方法"和
 * "前端非对话功能端点"两类职责. Batch 4 拆 AgentService 后, 4 个前端端点 (学习文档 /
 * 学习视频 / 反馈 / 导出) 已经各自指向独立服务 (FeatureDocumentLearnService /
 * VideoLearnService / FeedbackService / SessionExportService), 此 Controller 只剩
 * "薄薄的 HTTP 入口胶水". Batch 5-a 删 AgentController 整文件时, 必须把这 4 个
 * 端点搬到新 Controller, 否则前端 4 处调用立即 404.</p>
 *
 * <p><b>协议保持</b>: 路径前缀 / 方法 / 请求体 / 响应体一字不改, 前端零修改.
 * 唯一缺失的是已下线的 {@code /api/agent/chat/stream} (老同步对话入口),
 * 前端早已切到 {@code /api/graph/chat-stream}, 不影响.</p>
 *
 * @author wzh
 */
@Tag(name = "Agent 智能助手", description = "知识学习与反馈/导出等辅助功能")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentEndpointController {

    private final FeatureDocumentLearnService featureDocumentLearnService;
    private final FeedbackService feedbackService;
    private final SessionExportService sessionExportService;
    private final VideoLearnService videoLearnService;
    private final VideoDocumentService videoDocumentService;

    @Operation(summary = "学习功能文档")
    @PostMapping("/learn/{docId}")
    public Result<String> learnDocument(@PathVariable Long docId) {
        featureDocumentLearnService.learnDocument(docId);
        return Result.success("文档学习完成");
    }

    @Operation(summary = "学习功能文档关联的视频")
    @PostMapping("/learn/video/{featureId}")
    public Result<String> learnVideo(@PathVariable Long featureId) {
        List<VideoDocument> videos = videoDocumentService.getByFeatureId(featureId);
        if (videos.isEmpty()) {
            return Result.error("该功能文档没有关联视频");
        }
        boolean hasLearning = videos.stream()
                .anyMatch(v -> v.getLearnStatus() != null && v.getLearnStatus() == 1);
        if (hasLearning) {
            return Result.error("有视频正在学习中，请稍后再试");
        }
        videoLearnService.learnVideosAsync(featureId);
        return Result.success("视频学习任务已提交，请稍后查看状态");
    }

    @Operation(summary = "提交反馈（点赞/点踩）")
    @PostMapping("/feedback")
    public Result<String> submitFeedback(@RequestBody FeedbackRequest request) {
        feedbackService.submitFeedback(request.getMessageId(), request.getRating(), request.getReason());
        return Result.success("反馈已提交");
    }

    @Operation(summary = "导出会话为 Markdown")
    @GetMapping("/export/{sessionId}")
    public ResponseEntity<byte[]> exportSession(@PathVariable Long sessionId) {
        String markdown = sessionExportService.exportSessionAsMarkdown(sessionId);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=chat-export.md")
                .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=UTF-8")
                .body(bytes);
    }
}