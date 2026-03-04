package com.wzh.controller;

import com.wzh.common.Result;
import com.wzh.entity.dto.ChatRequest;
import com.wzh.entity.dto.FeedbackRequest;
import com.wzh.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;

@Tag(name = "Agent 智能助手", description = "知识学习与智能对话")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @Operation(summary = "学习功能文档")
    @PostMapping("/learn/{docId}")
    public Result<String> learnDocument(@PathVariable Long docId) {
        agentService.learnDocument(docId);
        return Result.success("文档学习完成");
    }

    @Operation(summary = "流式智能对话（SSE）- 支持图片上传")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        return agentService.chatStream(request.getSessionId(), request.getMessage(), request.getImageUrls());
    }

    @Operation(summary = "重新生成回答")
    @PostMapping(value = "/chat/regenerate/{messageId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerateMessage(@PathVariable Long messageId) {
        return agentService.regenerateMessage(messageId);
    }

    @Operation(summary = "提交反馈（点赞/点踩）")
    @PostMapping("/feedback")
    public Result<String> submitFeedback(@RequestBody FeedbackRequest request) {
        agentService.submitFeedback(request.getMessageId(), request.getRating(), request.getReason());
        return Result.success("反馈已提交");
    }

    @Operation(summary = "导出会话为 Markdown")
    @GetMapping("/export/{sessionId}")
    public ResponseEntity<byte[]> exportSession(@PathVariable Long sessionId) {
        String markdown = agentService.exportSessionAsMarkdown(sessionId);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=chat-export.md")
                .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=UTF-8")
                .body(bytes);
    }
}