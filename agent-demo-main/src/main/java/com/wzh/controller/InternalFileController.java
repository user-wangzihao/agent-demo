package com.wzh.controller;

import com.wzh.common.Result;
import com.wzh.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 内部文件接口(第五刀新增)。
 *
 * <p><b>路径前缀 /internal/ 而非 /api/internal/</b>:不带 /api 前缀, 
 * 自动绕过 AuthInterceptor(只拦 /api/**), 改用 X-Internal-Api-Key 自校验。
 * 设计对齐 InternalLearningController。</p>
 *
 * <p><b>用途</b>:供 TicketSystem 反向调用上传图片到 AgentDemo MinIO。
 * TicketSystem 侧技术员上传的 FAQ 候选图片最终归宿是 AgentDemo,
 * 走这个接口可以避免 TicketSystem 自建图床的复杂度。</p>
 *
 * @author wzh
 * @since 2026-05-15 (第五刀 Batch 2)
 */
@Slf4j
@RestController
@RequestMapping("/internal/file")
@RequiredArgsConstructor
public class InternalFileController {

    private final MinioService minioService;

    @Value("${internal.api-key}")
    private String expectedApiKey;

    /**
     * 上传单个文件到 AgentDemo MinIO。
     *
     * @param file       上传文件
     * @param apiKey     X-Internal-Api-Key Header(必须匹配 internal.api-key)
     * @return 文件URL
     */
    @PostMapping("/upload")
    public ResponseEntity<Result<String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        if (!verifyApiKey(apiKey)) {
            log.warn("[internal/file/upload] 鉴权失败 apiKey={}", maskKey(apiKey));
            return ResponseEntity.status(401)
                    .body(Result.error("X-Internal-Api-Key 校验失败"));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Result.error("上传文件不能为空"));
        }

        try {
            String url = minioService.uploadFile(file);
            log.info("[internal/file/upload] 上传成功 fileName={} url={}",
                    file.getOriginalFilename(), url);
            return ResponseEntity.ok(Result.success(url));
        } catch (Exception e) {
            log.error("[internal/file/upload] 上传失败 fileName={}",
                    file.getOriginalFilename(), e);
            return ResponseEntity.status(500)
                    .body(Result.error("上传失败:" + e.getMessage()));
        }
    }

    // ==================== 辅助 ====================

    private boolean verifyApiKey(String apiKey) {
        return apiKey != null && apiKey.equals(expectedApiKey);
    }

    private String maskKey(String key) {
        if (key == null) return "null";
        if (key.length() <= 4) return "***";
        return key.substring(0, 2) + "***" + key.substring(key.length() - 2);
    }
}