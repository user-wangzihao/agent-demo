package com.wzh.test;

import com.wzh.common.Result;
import com.wzh.config.RewriteProperties;
import com.wzh.service.DashScopeService;
import com.wzh.service.MilvusService;
import com.wzh.service.QueryRewriteService;
import com.wzh.service.VideoLearnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {
 
    private final VideoLearnService videoLearnService;

    @Autowired
    private DashScopeService dashScopeService;

    @Autowired
    private RewriteProperties rewriteProperties;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private MilvusService milvusService;
 
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

    // http://localhost:9999/api/test/admin/test/chatOnce?prompt=你是谁?
    @GetMapping("/admin/test/chatOnce")
    public String testChatOnce(@RequestParam(defaultValue = "qwen-turbo") String model,
                               @RequestParam String prompt) {
        return dashScopeService.chatOnce(
                model,
                "你是一个简洁的助手，用一句话回答问题。",
                prompt,
                0.2f,
                200,
                null
        );
    }

    // http://localhost:9999/api/test/admin/test/rewriteConfig
    @GetMapping("/admin/test/rewriteConfig")
    public RewriteProperties testRewriteConfig() {
        return rewriteProperties;
    }

    /**
     * 测试改写
     * @param query
     * @return
     * "http://localhost:9999/api/test/admin/test/rewrite?query=我在使用快速涂色功能的时候,为什么上色功能失败?弹出个提示框,说是找不到配置表。"
     * "http://localhost:9999/api/test/admin/test/rewrite?query=如何使用赋属性这个功能?"
     * "http://localhost:9999/api/test/admin/test/rewrite?query=快速涂色功能具体如何使用?"
     * "http://localhost:9999/api/test/admin/test/rewrite?query=快速涂色这个功能主要用户做什么?"
     * "http://localhost:9999/api/test/admin/test/rewrite?query=介绍一下赋注解属性这个功能的作用。"
     * "http://localhost:9999/api/test/admin/test/rewrite?query=如何使用建模出图工具这个功能?"
     * "http://localhost:9999/api/test/admin/test/rewrite?query=使用建模档合并订料功能,弹出备料板重量超出限定值的提示"
     */
    @GetMapping("/admin/test/rewrite")
    public Map<String, Object> testRewrite(@RequestParam String query) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> rewrites = queryRewriteService.rewrite(query);
            result.put("original", query);
            result.put("rewrites", rewrites);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("success", true);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("success", false);
        }
        return result;
    }

    // http://localhost:9999/api/test/admin/test/searchByFeatures?query=快速涂色为什么失败&features=快速涂色&topK=5
    @GetMapping("/admin/test/searchByFeatures")
    public Map<String, Object> testSearchByFeatures(
            @RequestParam String query,
            @RequestParam(required = false) String features,  // 逗号分隔,如 "快速涂色,BOM工具"
            @RequestParam(defaultValue = "5") int topK) {
        // 1. 把 query 向量化
        List<Float> vector = dashScopeService.getEmbedding(query);

        // 2. 解析 features 参数
        List<String> featureList = null;
        if (features != null && !features.trim().isEmpty()) {
            featureList = Arrays.asList(features.split(","));
        }

        // 3. 检索
        long start = System.currentTimeMillis();
        List<MilvusService.SearchResult> results = milvusService.searchByFeatures(vector, featureList, topK);
        long latency = System.currentTimeMillis() - start;

        // 4. 简化返回
        Map<String, Object> resp = new HashMap<>();
        resp.put("query", query);
        resp.put("features", featureList);
        resp.put("count", results.size());
        resp.put("latencyMs", latency);
        resp.put("results", results.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("chunkId", r.chunkId);
            m.put("featureName", r.featureName);
            m.put("score", r.score);
            m.put("contentPreview", r.content == null ? "" :
                    r.content.substring(0, Math.min(100, r.content.length())));
            return m;
        }).toList());
        return resp;
    }

}