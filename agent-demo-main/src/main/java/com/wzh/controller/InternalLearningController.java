package com.wzh.controller;

import com.wzh.service.AgentService;
import com.wzh.service.VideoLearnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 供 MCP Server 调用的内部接口：触发文档/视频学习。
 * 通过 X-Internal-Api-Key 做简单鉴权，避免被外部直接调用。
 */
@Slf4j
@RestController
@RequestMapping("/internal/learning")
@RequiredArgsConstructor
public class InternalLearningController {

    private final AgentService agentService;
    private final VideoLearnService videoLearnService;

    @Value("${internal.api-key:internal-secret-key-change-me}")
    private String expectedApiKey;

    @PostMapping("/document/{docId}")
    public ResponseEntity<Map<String, Object>> triggerDocumentLearning(
            @PathVariable Long docId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey
    ) {
        if (!expectedApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false, "message", "无效的内部 API Key"));
        }
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[内部接口-异步] 开始学习文档 id={}", docId);
                agentService.learnDocument(docId);
                log.info("[内部接口-异步] 文档学习完成 id={}", docId);
            } catch (Exception e) {
                log.error("[内部接口-异步] 文档学习失败 id={}", docId, e);
            }
        });
        return ResponseEntity.ok(Map.of(
                "success", true,
                "docId", docId,
                "message", "已触发文档学习任务，正在后台执行"));
    }

    @PostMapping("/video/{videoId}")
    public ResponseEntity<Map<String, Object>> triggerVideoLearning(
            @PathVariable Long videoId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey
    ) {
        if (!expectedApiKey.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false, "message", "无效的内部 API Key"));
        }
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[内部接口-异步] 开始学习视频 id={}", videoId);
                videoLearnService.learnVideoById(videoId);
                log.info("[内部接口-异步] 视频学习完成 id={}", videoId);
            } catch (Exception e) {
                log.error("[内部接口-异步] 视频学习失败 id={}", videoId, e);
            }
        });
        return ResponseEntity.ok(Map.of(
                "success", true,
                "videoId", videoId,
                "message", "已触发视频学习任务，正在后台执行"));
    }
}