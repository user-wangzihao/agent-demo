package com.wzh.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.*;
import com.wzh.common.UserContext;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import com.wzh.agentdemo.common.mapper.ChatSessionMapper;
import com.wzh.agentdemo.common.mapper.FeatureDocumentMapper;
import com.wzh.agentdemo.common.mapper.VideoDocumentMapper;
import com.wzh.agentdemo.common.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 知识库工具服务
 * 封装两个 Agent 可调用的工具：
 * 1. triggerKnowledgeLearning  — 触发文档/视频学习
 * 2. analyzeUsageStats         — 使用情况统计分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeToolService {

    private final FeatureDocumentMapper featureDocumentMapper;
    private final VideoDocumentMapper videoDocumentMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final SysUserMapper sysUserMapper;
    private final AgentService agentService;
    private final VideoLearnService videoLearnService;
    private final ObjectMapper objectMapper;

    // ==================== 停用词（用于关键词统计过滤）====================

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "吗", "呢", "啊", "哦", "嗯", "是", "在", "有", "我", "你", "他", "她",
            "们", "这", "那", "什么", "怎么", "如何", "为什么", "可以", "能够", "需要", "一个",
            "一些", "就是", "但是", "所以", "因为", "如果", "然后", "还是", "已经", "没有",
            "不是", "不能", "请问", "问题", "帮我", "帮助", "一下", "这个", "那个", "哪个",
            "and", "the", "is", "are", "can", "how", "what", "why", "when"
    );

    // ==================== 工具一：查询所有文档/视频学习状态 ====================

    /**
     * 查询所有文档和视频的学习状态（已学习 + 未学习 + 学习失败），供 Agent 展示给管理员
     * 原方法名 listUnlearnedDocuments，现改为 listDocumentStatus 以覆盖全量查询场景
     */
    public String listDocumentStatus() {
        if (!isAdmin()) return "{\"error\": \"权限不足，仅管理员可执行此操作\"}";
        try {
            // 查询所有未删除的功能文档
            List<FeatureDocument> allDocs = featureDocumentMapper.selectList(
                    new LambdaQueryWrapper<FeatureDocument>()
                            .eq(FeatureDocument::getDeleted, 0)
                            .select(FeatureDocument::getId, FeatureDocument::getFeatureName,
                                    FeatureDocument::getVectorized)
                            .orderByAsc(FeatureDocument::getId)
            );

            // 查询所有未删除的视频
            List<VideoDocument> allVideos = videoDocumentMapper.selectList(
                    new LambdaQueryWrapper<VideoDocument>()
                            .eq(VideoDocument::getDeleted, 0)
                            .select(VideoDocument::getId, VideoDocument::getOriginalName,
                                    VideoDocument::getFeatureId, VideoDocument::getLearnStatus)
                            .orderByAsc(VideoDocument::getId)
            );

            // 文档列表：带学习状态
            List<Map<String, Object>> docList = allDocs.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("featureName", d.getFeatureName());
                m.put("type", "document");
                m.put("status", d.getVectorized() == 1 ? "已学习" : "未学习");
                return m;
            }).collect(Collectors.toList());

            // 视频列表：带学习状态
            List<Map<String, Object>> videoList = allVideos.stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.getId());
                m.put("name", v.getOriginalName());
                m.put("featureId", v.getFeatureId());
                m.put("type", "video");
                String statusLabel = switch (v.getLearnStatus()) {
                    case 1 -> "学习中";
                    case 2 -> "已学习";
                    case 3 -> "学习失败";
                    default -> "未学习";
                };
                m.put("status", statusLabel);
                return m;
            }).collect(Collectors.toList());

            // 统计数量
            long learnedDocCount = allDocs.stream().filter(d -> d.getVectorized() == 1).count();
            long unlearnedDocCount = allDocs.size() - learnedDocCount;
            long learnedVideoCount = allVideos.stream().filter(v -> v.getLearnStatus() == 2).count();
            long unlearnedVideoCount = allVideos.size() - learnedVideoCount;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalDocCount", allDocs.size());
            result.put("learnedDocCount", learnedDocCount);
            result.put("unlearnedDocCount", unlearnedDocCount);
            result.put("totalVideoCount", allVideos.size());
            result.put("learnedVideoCount", learnedVideoCount);
            result.put("unlearnedVideoCount", unlearnedVideoCount);
            result.put("documents", docList);
            result.put("videos", videoList);
            result.put("message", "查询成功");

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("查询文档学习状态失败", e);
            return "{\"error\": \"查询失败: " + e.getMessage() + "\"}";
        }
    }

    // ==================== 工具二：触发知识库学习 ====================

    /**
     * 触发知识库学习（异步执行）
     *
     * @param scope    "all_unlearned"=全部未学习, "doc_{id}"=指定文档, "video_{id}"=指定视频
     * @return 立即返回触发结果（不等待学习完成）
     */
    public String triggerKnowledgeLearning(String scope) {
        if (!isAdmin()) return "{\"success\": false, \"message\": \"权限不足，仅管理员可执行此操作\"}";
        try {
            if ("all_unlearned".equals(scope)) {
                return triggerAllUnlearned();
            } else if (scope != null && scope.startsWith("doc_")) {
                long docId = Long.parseLong(scope.substring(4));
                return triggerDocumentLearning(docId);
            } else if (scope != null && scope.startsWith("video_")) {
                long videoId = Long.parseLong(scope.substring(6));
                return triggerVideoLearning(videoId);
            } else {
                return "{\"success\": false, \"message\": \"无效的学习范围参数: " + scope + "\"}";
            }
        } catch (NumberFormatException e) {
            return "{\"success\": false, \"message\": \"无效的ID格式\"}";
        } catch (Exception e) {
            log.error("触发知识库学习失败 scope={}", scope, e);
            return "{\"success\": false, \"message\": \"触发失败: " + e.getMessage() + "\"}";
        }
    }

    private String triggerAllUnlearned() throws Exception {
        List<FeatureDocument> unlearnedDocs = featureDocumentMapper.selectList(
                new LambdaQueryWrapper<FeatureDocument>()
                        .eq(FeatureDocument::getVectorized, 0)
                        .eq(FeatureDocument::getDeleted, 0)
        );

        List<VideoDocument> unlearnedVideos = videoDocumentMapper.selectList(
                new LambdaQueryWrapper<VideoDocument>()
                        .in(VideoDocument::getLearnStatus, 0, 3)
                        .eq(VideoDocument::getDeleted, 0)
        );

        if (unlearnedDocs.isEmpty() && unlearnedVideos.isEmpty()) {
            return "{\"success\": true, \"message\": \"所有文档和视频均已学习完毕，无需重新学习\"}";
        }

        int docCount = unlearnedDocs.size();
        int videoCount = unlearnedVideos.size();

        CompletableFuture.runAsync(() -> {
            for (FeatureDocument doc : unlearnedDocs) {
                try {
                    log.info("[异步学习] 开始学习文档: {} (id={})", doc.getFeatureName(), doc.getId());
                    agentService.learnDocument(doc.getId());
                    log.info("[异步学习] 文档学习完成: {}", doc.getFeatureName());
                } catch (Exception e) {
                    log.error("[异步学习] 文档学习失败: {} - {}", doc.getFeatureName(), e.getMessage());
                }
            }
            for (VideoDocument video : unlearnedVideos) {
                try {
                    log.info("[异步学习] 开始学习视频: {} (id={})", video.getOriginalName(), video.getId());
                    videoLearnService.learnVideoById(video.getId());
                    log.info("[异步学习] 视频学习完成: {}", video.getOriginalName());
                } catch (Exception e) {
                    log.error("[异步学习] 视频学习失败: {} - {}", video.getOriginalName(), e.getMessage());
                }
            }
            log.info("[异步学习] 全部任务完成，共学习 {} 篇文档、{} 个视频", docCount, videoCount);
        });

        return String.format(
                "{\"success\": true, \"message\": \"已触发学习任务，共 %d 篇文档、%d 个视频正在后台学习中，请稍后查询进度\", \"docCount\": %d, \"videoCount\": %d}",
                docCount, videoCount, docCount, videoCount
        );
    }

    private String triggerDocumentLearning(long docId) {
        FeatureDocument doc = featureDocumentMapper.selectById(docId);
        if (doc == null || doc.getDeleted() == 1) {
            return "{\"success\": false, \"message\": \"文档不存在或已删除 (id=" + docId + ")\"}";
        }

        final String featureName = doc.getFeatureName();

        CompletableFuture.runAsync(() -> {
            try {
                log.info("[异步学习] 开始学习文档: {} (id={})", featureName, docId);
                agentService.learnDocument(docId);

                List<VideoDocument> relatedVideos = videoDocumentMapper.selectList(
                        new LambdaQueryWrapper<VideoDocument>()
                                .eq(VideoDocument::getFeatureId, docId)
                                .in(VideoDocument::getLearnStatus, 0, 3)
                                .eq(VideoDocument::getDeleted, 0)
                );
                for (VideoDocument video : relatedVideos) {
                    try {
                        log.info("[异步学习] 同时学习关联视频: {}", video.getOriginalName());
                        videoLearnService.learnVideoById(video.getId());
                    } catch (Exception e) {
                        log.error("[异步学习] 关联视频学习失败: {}", e.getMessage());
                    }
                }
                log.info("[异步学习] 文档 {} 及关联视频学习完成", featureName);
            } catch (Exception e) {
                log.error("[异步学习] 文档学习失败 id={}: {}", docId, e.getMessage());
            }
        });

        return String.format(
                "{\"success\": true, \"message\": \"已触发【%s】的学习任务，正在后台处理中\", \"docId\": %d, \"featureName\": \"%s\"}",
                featureName, docId, featureName
        );
    }

    private String triggerVideoLearning(long videoId) {
        VideoDocument video = videoDocumentMapper.selectById(videoId);
        if (video == null || video.getDeleted() == 1) {
            return "{\"success\": false, \"message\": \"视频不存在或已删除 (id=" + videoId + ")\"}";
        }

        final String videoName = video.getOriginalName();

        CompletableFuture.runAsync(() -> {
            try {
                log.info("[异步学习] 开始学习视频: {} (id={})", videoName, videoId);
                videoLearnService.learnVideoById(videoId);
                log.info("[异步学习] 视频学习完成: {}", videoName);
            } catch (Exception e) {
                log.error("[异步学习] 视频学习失败 id={}: {}", videoId, e.getMessage());
            }
        });

        return String.format(
                "{\"success\": true, \"message\": \"已触发视频【%s】的学习任务，正在后台处理中\", \"videoId\": %d}",
                videoName, videoId
        );
    }

    // ==================== 工具三：使用情况统计分析 ====================

    /**
     * 统计指定时间范围内的使用情况
     */
    public String analyzeUsageStats(String timeRange) {
        if (!isAdmin()) return "{\"error\": \"权限不足，仅管理员可执行此操作\"}";
        try {
            LocalDateTime[] range = parseTimeRange(timeRange);
            LocalDateTime startTime = range[0];
            LocalDateTime endTime = range[1];

            List<ChatSession> sessions = chatSessionMapper.selectList(
                    new LambdaQueryWrapper<ChatSession>()
                            .eq(ChatSession::getDeleted, 0)
                            .between(ChatSession::getCreateTime, startTime, endTime)
            );

            Set<Long> sessionIds = sessions.stream().map(ChatSession::getId).collect(Collectors.toSet());
            Set<Long> userIds = sessions.stream().map(ChatSession::getUserId).collect(Collectors.toSet());

            if (sessionIds.isEmpty()) {
                return buildEmptyStatsResult(timeRange, startTime, endTime);
            }

            List<ChatMessage> allMessages = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .in(ChatMessage::getSessionId, sessionIds)
            );

            List<ChatMessage> userMessages = allMessages.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .collect(Collectors.toList());

            List<ChatMessage> assistantMessages = allMessages.stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .collect(Collectors.toList());

            long likeCount = assistantMessages.stream()
                    .filter(m -> m.getFeedbackRating() != null && m.getFeedbackRating() == 1)
                    .count();
            long dislikeCount = assistantMessages.stream()
                    .filter(m -> m.getFeedbackRating() != null && m.getFeedbackRating() == -1)
                    .count();

            Map<Long, Long> userSessionCount = sessions.stream()
                    .collect(Collectors.groupingBy(ChatSession::getUserId, Collectors.counting()));

            List<Map<String, Object>> topUsers = userSessionCount.entrySet().stream()
                    .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> {
                        SysUser user = sysUserMapper.selectById(e.getKey());
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("userId", e.getKey());
                        m.put("nickname", user != null ? user.getNickname() : "未知用户");
                        m.put("sessionCount", e.getValue());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Integer> keywordFreq = extractKeywordFrequency(userMessages);
            List<Map<String, Object>> topKeywords = keywordFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("keyword", e.getKey());
                        m.put("count", e.getValue());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timeRange", timeRange);
            result.put("period", startTime.format(DateTimeFormatter.ofPattern("MM-dd")) +
                    " 至 " + endTime.format(DateTimeFormatter.ofPattern("MM-dd")));
            result.put("totalSessions", sessions.size());
            result.put("activeUsers", userIds.size());
            result.put("totalUserMessages", userMessages.size());
            result.put("avgMessagesPerSession",
                    sessions.isEmpty() ? 0 : Math.round((double) userMessages.size() / sessions.size() * 10) / 10.0);
            result.put("likeCount", likeCount);
            result.put("dislikeCount", dislikeCount);
            result.put("satisfactionRate",
                    (likeCount + dislikeCount) == 0 ? "暂无反馈数据" :
                            Math.round((double) likeCount / (likeCount + dislikeCount) * 100) + "%");
            result.put("topActiveUsers", topUsers);
            result.put("topKeywords", topKeywords);

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("使用情况统计分析失败", e);
            return "{\"error\": \"统计分析失败: " + e.getMessage() + "\"}";
        }
    }

    // ==================== 私有辅助方法 ====================

    private LocalDateTime[] parseTimeRange(String timeRange) {
        LocalDate today = LocalDate.now();
        LocalDateTime start, end;

        switch (timeRange == null ? "this_week" : timeRange) {
            case "last_week" -> {
                LocalDate lastMonday = today.with(DayOfWeek.MONDAY).minusWeeks(1);
                start = lastMonday.atStartOfDay();
                end = lastMonday.plusDays(6).atTime(23, 59, 59);
            }
            case "this_month" -> {
                start = today.withDayOfMonth(1).atStartOfDay();
                end = LocalDateTime.now();
            }
            case "last_30_days" -> {
                start = today.minusDays(30).atStartOfDay();
                end = LocalDateTime.now();
            }
            default -> {
                LocalDate monday = today.with(DayOfWeek.MONDAY);
                start = monday.atStartOfDay();
                end = LocalDateTime.now();
            }
        }
        return new LocalDateTime[]{start, end};
    }

    private Map<String, Integer> extractKeywordFrequency(List<ChatMessage> userMessages) {
        Map<String, Integer> freq = new HashMap<>();
        for (ChatMessage msg : userMessages) {
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            String[] tokens = msg.getContent().split("[\\s，。！？、：；\"'【】（）()\\[\\],.!?;:]+");
            for (String token : tokens) {
                String t = token.trim();
                if (t.length() >= 2 && !STOP_WORDS.contains(t)) {
                    freq.merge(t, 1, Integer::sum);
                }
            }
        }
        freq.entrySet().removeIf(e -> e.getValue() < 2);
        return freq;
    }

    private String buildEmptyStatsResult(String timeRange, LocalDateTime start, LocalDateTime end) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeRange", timeRange);
        result.put("period", start.format(DateTimeFormatter.ofPattern("MM-dd")) +
                " 至 " + end.format(DateTimeFormatter.ofPattern("MM-dd")));
        result.put("totalSessions", 0);
        result.put("activeUsers", 0);
        result.put("totalUserMessages", 0);
        result.put("message", "该时间段内暂无使用数据");
        return objectMapper.writeValueAsString(result);
    }

    // ==================== 权限校验 ====================

    private boolean isAdmin() {
        try {
            Long userId = UserContext.getUserId();
            if (userId == null) return false;
            SysUser user = sysUserMapper.selectById(userId);
            return user != null && "admin".equals(user.getRole());
        } catch (Exception e) {
            log.warn("权限校验异常，默认拒绝: {}", e.getMessage());
            return false;
        }
    }
}