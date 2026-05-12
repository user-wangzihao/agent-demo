package com.wzh.graph.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.service.AgentService;
import com.wzh.service.MilvusService.SearchResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索结果后处理工具 (Graph 模式专用).
 *
 * <p><b>由来</b>: 把 AgentService 里的私有方法 postProcessSearchResults / collectImages /
 * isKnowledgeChunkType 抽到独立工具类, 供 MergerNode 调用. AgentService 的私有方法保留不动,
 * 避免影响老链路. 第六刀 AgentService 下线时统一清理.</p>
 *
 * <p><b>核心逻辑 (postProcess)</b>:
 * <ol>
 *   <li>分数过滤: score >= 0.5 优先, 不足则降级到 >= 0.3</li>
 *   <li>分桶: 知识 chunk / 文本 chunk / 图片 chunk 三类</li>
 *   <li>整合: 知识 chunk 全保留, 同 feature 的文本 chunk 取最高分, 图片 chunk 末尾追加</li>
 *   <li>截断: 文本+知识结果总数 ≤ 4</li>
 * </ol></p>
 *
 * <p><b>注意</b>: 这里的 score 阈值 (0.5 / 0.3) 是 AgentService 老代码的硬编码,
 * 对 RRF / Reranker 后的 score 含义不严谨, 是已知技术债 (backlog: "AgentService score 阈值修正").</p>
 *
 * @author wzh
 * @since 2026-05-11
 */
public final class RetrievalPostProcessor {

    private RetrievalPostProcessor() {
        // 工具类禁止实例化
    }

    /**
     * 对原始检索结果做后处理 (分数过滤 + 分桶 + 整合 + 截断).
     */
    public static List<SearchResult> postProcess(List<SearchResult> rawResults) {
        if (rawResults == null || rawResults.isEmpty()) return new ArrayList<>();
        List<SearchResult> filtered = rawResults.stream()
                .filter(sr -> sr.score >= 0.5f).collect(Collectors.toList());
        if (filtered.isEmpty()) {
            filtered = rawResults.stream().filter(sr -> sr.score >= 0.3f).toList();
        }
        if (filtered.isEmpty()) return new ArrayList<>();

        List<SearchResult> knowledgeResults = new ArrayList<>();
        List<SearchResult> textResults = new ArrayList<>();
        List<SearchResult> imageResults = new ArrayList<>();
        for (SearchResult sr : filtered) {
            if (isKnowledgeChunkType(sr.chunkType)) knowledgeResults.add(sr);
            else if ("image_description".equals(sr.chunkType)) imageResults.add(sr);
            else textResults.add(sr);
        }

        List<SearchResult> finalResults = new ArrayList<>(knowledgeResults);
        if (!knowledgeResults.isEmpty()) {
            Set<String> covered = knowledgeResults.stream()
                    .map(sr -> sr.featureName).collect(Collectors.toSet());
            Map<String, SearchResult> best = new LinkedHashMap<>();
            for (SearchResult sr : textResults) {
                if (covered.contains(sr.featureName)) {
                    best.merge(sr.featureName, sr, (a, b) -> b.score > a.score ? b : a);
                } else {
                    finalResults.add(sr);
                }
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

    /**
     * 判断 chunkType 是否为"知识抽取"类型 (error_solution / prerequisite / caution / dependency).
     */
    public static boolean isKnowledgeChunkType(String chunkType) {
        return "error_solution".equals(chunkType) || "prerequisite".equals(chunkType)
                || "caution".equals(chunkType) || "dependency".equals(chunkType);
    }

    /**
     * 从 chunk 的 imageUrls 字段提取图片 URL, 合并到 target 中 (去重保序).
     *
     * <p><b>容错</b>: imageUrls 可能是 List<String> / JSON 字符串 / 逗号分隔字符串, 三种都兼容.</p>
     */
    public static void collectImages(Object imageUrlsObj, List<String> target, ObjectMapper objectMapper) {
        if (imageUrlsObj == null) return;
        if (imageUrlsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> imgList = (List<String>) imageUrlsObj;
            for (String url : imgList) {
                String t = url.trim();
                if (!t.isEmpty() && !target.contains(t)) target.add(t);
            }
        } else if (imageUrlsObj instanceof String str) {
            if (StrUtil.isBlank(str) || "[]".equals(str)) return;
            if (str.startsWith("[")) {
                try {
                    List<String> imgs = objectMapper.readValue(str,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    for (String url : imgs) {
                        String t = url.trim();
                        if (!t.isEmpty() && !target.contains(t)) target.add(t);
                    }
                    return;
                } catch (Exception ignored) {
                }
            }
            for (String url : str.split(",")) {
                String t = url.trim();
                if (!t.isEmpty() && !target.contains(t)) target.add(t);
            }
        }
    }

    /**
     * 把 SearchResult 列表转成 AgentService.SourceInfo 列表 (过滤掉 image_description 类型).
     *
     * <p><b>注意</b>: 复用 AgentService.SourceInfo 这个内部类, 保持前端协议一致.</p>
     */
    public static List<AgentService.SourceInfo> toSourceInfoList(List<SearchResult> results) {
        return results.stream()
                .filter(sr -> !"image_description".equals(sr.chunkType))
                .map(sr -> {
                    AgentService.SourceInfo s = new AgentService.SourceInfo();
                    s.featureName = sr.featureName;
                    s.chunkType = sr.chunkType;
                    s.score = sr.score;
                    return s;
                })
                .collect(Collectors.toList());
    }
}