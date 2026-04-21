package com.wzh.controller;

import com.wzh.common.Result;
import com.wzh.agentdemo.common.entity.VideoDocument;
import com.wzh.service.MinioService;
import com.wzh.service.VideoDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "视频管理", description = "视频上传与管理接口")
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoDocumentService videoDocumentService;
    private final MinioService minioService;

    @Operation(summary = "上传视频")
    @PostMapping("/upload")
    public Result<VideoDocument> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "featureId", required = false) Long featureId) {
        // 1. 上传到MinIO
        String url = minioService.uploadFile(file);

        // 2. 保存视频记录
        VideoDocument video = new VideoDocument();
        video.setFeatureId(featureId);
        video.setOriginalName(file.getOriginalFilename());
        video.setFileUrl(url);
        video.setFileSize(file.getSize());
        video.setFileType(file.getContentType());
        video.setLearnStatus(0);
        videoDocumentService.save(video);

        return Result.success(video);
    }

    @Operation(summary = "查询功能文档关联的视频列表")
    @GetMapping("/list/{featureId}")
    public Result<List<VideoDocument>> list(@PathVariable Long featureId) {
        return Result.success(videoDocumentService.getByFeatureId(featureId));
    }

    @Operation(summary = "删除视频")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        videoDocumentService.removeById(id);
        return Result.success();
    }

    @Operation(summary = "批量关联视频到功能文档")
    @PostMapping("/bind/{featureId}")
    public Result<Void> bind(@PathVariable Long featureId, @RequestBody List<Long> videoIds) {
        if (videoIds != null && !videoIds.isEmpty()) {
            videoIds.forEach(id -> {
                VideoDocument video = videoDocumentService.getById(id);
                if (video != null) {
                    video.setFeatureId(featureId);
                    videoDocumentService.updateById(video);
                }
            });
        }
        return Result.success();
    }


}