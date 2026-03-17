package com.wzh;

import com.wzh.service.VideoLearnService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = AgentDemoApplication.class)
@Slf4j
public class LearnVideoTest {

    @Autowired
    private VideoLearnService videoLearnService;

    @Test
    public void test() {
        // ====== 在这里写死你的视频URL ======
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
            log.info("========== 测试结束 ==========");
        } catch (Exception e) {
            log.error("视频分析失败", e);
        }
    }

}
