package com.wzh.test;

import com.wzh.common.Result;
import com.wzh.service.VideoLearnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {
 
    private final VideoLearnService videoLearnService;
 
    /**
     * 测试视频分析（同步执行，结果打印到控制台）
     * 调用方式：GET http://localhost:9999/api/test/video-analyze
     */
    @GetMapping("/video-analyze")
    public Result<String> testVideoAnalyze() {
        // ====== 在这里写死视频URL ======
        String videoUrl = "http://36.150.236.251:9000/agent-demo/2026/03/17/f886d77cf5cd4d48b706c248e22c9460.mp4";
        String featureName = "测试功能";
        // ==================================
 
        log.info("========== 开始测试视频分析 ==========");
        log.info("视频URL: {}", videoUrl);
 
        try {
            String result = videoLearnService.analyzeVideoForTest(videoUrl, featureName);
 
            log.info("========== 视频分析结果 ==========");
            log.info("\n{}", result);
            log.info("========== 分析结果长度: {} 字符 ==========", result.length());
 
            return Result.success(result);
        } catch (Exception e) {
            log.error("视频分析失败", e);
            return Result.error("分析失败: " + e.getMessage());
        }
    }

}