package com.wzh.agentdemo.mcp.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wzh.agentdemo.mcp.client.MainAppClient;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.entity.ChatSession;
import com.wzh.agentdemo.common.entity.FeatureDocument;
import com.wzh.agentdemo.common.entity.SysUser;
import com.wzh.agentdemo.common.entity.VideoDocument;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import com.wzh.agentdemo.common.mapper.ChatSessionMapper;
import com.wzh.agentdemo.common.mapper.FeatureDocumentMapper;
import com.wzh.agentdemo.common.mapper.SysUserMapper;
import com.wzh.agentdemo.common.mapper.VideoDocumentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库相关工具（4 个）：
 *   listDocumentStatus       — 查询所有文档/视频的学习状态
 *   retrievalSource          — 查询最近一次 Agent 回答的引用来源
 *   analyzeUsageStats        — 使用情况统计分析
 *   triggerKnowledgeLearning — 触发文档/视频学习（异步，回调主应用）
 *
 * 前三个直查 MySQL；最后一个通过 HTTP 回调主应用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTools {

    private final FeatureDocumentMapper featureDocumentMapper;
    private final VideoDocumentMapper videoDocumentMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final SysUserMapper sysUserMapper;
    private final MainAppClient mainAppClient;
    private final ObjectMapper objectMapper;

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "吗", "呢", "啊", "哦", "嗯", "是", "在", "有",
            "我", "你", "他", "她", "们", "这", "那",
            "什么", "怎么", "如何", "为什么", "可以", "能够", "需要",
            "一个", "一些", "就是", "但是", "所以", "因为", "如果", "然后",
            "还是", "已经", "没有", "不是", "不能",
            "请问", "问题", "帮我", "帮助", "一下", "这个", "那个", "哪个",
            "and", "the", "is", "are", "can", "how", "what", "why", "when"
    );

    // ==================== 工具 1：查询所有文档/视频学习状态 ====================

    @Tool(
            name = "listDocumentStatus",
            description = """
                    查询知识库中所有文档和视频的学习状态（已学习 / 未学习 / 学习失败）。
                    管理员想知道"哪些知识还没学习"或"知识库里有什么"时调用。
                    仅管理员可用。
                    """
    )
    public String listDocumentStatus() {
        try {
            List<FeatureDocument> allDocs = featureDocumentMapper.selectList(
                    new LambdaQueryWrapper<FeatureDocument>()
                            .eq(FeatureDocument::getDeleted, 0)
                            .select(FeatureDocument::getId,
                                    FeatureDocument::getFeatureName,
                                    FeatureDocument::getVectorized)
                            .orderByAsc(FeatureDocument::getId)
            );

            List<VideoDocument> allVideos = videoDocumentMapper.selectList(
                    new LambdaQueryWrapper<VideoDocument>()
                            .eq(VideoDocument::getDeleted, 0)
                            .select(VideoDocument::getId,
                                    VideoDocument::getOriginalName,
                                    VideoDocument::getFeatureId,
                                    VideoDocument::getLearnStatus)
                            .orderByAsc(VideoDocument::getId)
            );

            List<Map<String, Object>> docList = allDocs.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("featureName", d.getFeatureName());
                m.put("type", "document");
                m.put("status", d.getVectorized() == 1 ? "learned" : "unlearned");
                return m;
            }).collect(Collectors.toList());

            List<Map<String, Object>> videoList = allVideos.stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.getId());
                m.put("originalName", v.getOriginalName());
                m.put("featureId", v.getFeatureId());
                m.put("type", "video");
                m.put("status", mapVideoStatus(v.getLearnStatus()));
                return m;
            }).collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("documentTotal", docList.size());
            result.put("videoTotal", videoList.size());
            result.put("unlearnedDocCount",
                    docList.stream().filter(d -> "unlearned".equals(d.get("status"))).count());
            result.put("unlearnedVideoCount",
                    videoList.stream().filter(v -> "unlearned".equals(v.get("status"))).count());
            result.put("documents", docList);
            result.put("videos", videoList);

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("listDocumentStatus 失败", e);
            return errorJson(e.getMessage());
        }
    }

    // ==================== 工具 2：检索来源溯源 ====================

    @Tool(
            name = "retrievalSource",
            description = """
                    查询指定会话最近一次 AI 回答所引用的知识库来源。
                    用户追问"刚才的回答来自哪篇文档"或"来源是什么"时调用。
                    """
    )
    public String retrievalSource(
            @ToolParam(description = "当前会话 ID")
            Long sessionId
    ) {
        try {
            ChatMessage latestAssistant = chatMessageMapper.selectOne(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, sessionId)
                            .eq(ChatMessage::getRole, "assistant")
                            .orderByDesc(ChatMessage::getCreateTime)
                            .last("LIMIT 1")
            );

            if (latestAssistant == null) {
                return objectMapper.writeValueAsString(Map.of(
                        "success", false,
                        "message", "该会话还没有 AI 回答，无法查询来源"
                ));
            }

            String sourcesJson = latestAssistant.getSources();
            if (sourcesJson == null || sourcesJson.isBlank() || "[]".equals(sourcesJson)) {
                return objectMapper.writeValueAsString(Map.of(
                        "success", true,
                        "hasSources", false,
                        "message", "上次回答未使用知识库检索（可能是通用对话或工具调用结果）"
                ));
            }

            List<Map<String, Object>> sources = objectMapper.readValue(
                    sourcesJson, new TypeReference<>() {}
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("hasSources", true);
            result.put("messageId", latestAssistant.getId());
            result.put("sourceCount", sources.size());
            result.put("sources", sources);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("retrievalSource 失败 sessionId={}", sessionId, e);
            return errorJson(e.getMessage());
        }
    }

    // ==================== 工具 3：使用情况统计分析 ====================

    @Tool(
            name = "analyzeUsageStats",
            description = """
                    统计指定时间范围内的系统使用情况：总对话数、活跃用户 Top N、
                    高频关键词、反馈点赞/踩比率等。
                    管理员询问"本周使用情况""哪些用户问得最多"时调用。仅管理员可用。
                    """
    )
    public String analyzeUsageStats(
            @ToolParam(description = "时间范围：this_week(本周), last_week(上周), this_month(本月), last_30_days(近30天)")
            String timeRange
    ) {
        try {
            LocalDateTime[] range = parseTimeRange(timeRange);
            LocalDateTime startTime = range[0];
            LocalDateTime endTime = range[1];

            List<ChatSession> sessions = chatSessionMapper.selectList(
                    new LambdaQueryWrapper<ChatSession>()
                            .eq(ChatSession::getDeleted, 0)
                            .between(ChatSession::getCreateTime, startTime, endTime)
            );

            if (sessions.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                        "success", true,
                        "timeRange", timeRange,
                        "empty", true,
                        "message", "该时间段内暂无对话数据"
                ));
            }

            Set<Long> sessionIds = sessions.stream()
                    .map(ChatSession::getId).collect(Collectors.toSet());
            Set<Long> userIds = sessions.stream()
                    .map(ChatSession::getUserId).collect(Collectors.toSet());

            List<ChatMessage> allMessages = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .in(ChatMessage::getSessionId, sessionIds)
            );

            long userMessageCount = allMessages.stream()
                    .filter(m -> "user".equals(m.getRole())).count();
            long assistantMessageCount = allMessages.stream()
                    .filter(m -> "assistant".equals(m.getRole())).count();

            long likeCount = allMessages.stream()
                    .filter(m -> m.getFeedbackRating() != null && m.getFeedbackRating() == 1)
                    .count();
            long dislikeCount = allMessages.stream()
                    .filter(m -> m.getFeedbackRating() != null && m.getFeedbackRating() == -1)
                    .count();

            // 活跃用户 Top 5
            Map<Long, Long> userSessionCount = sessions.stream()
                    .collect(Collectors.groupingBy(
                            ChatSession::getUserId, Collectors.counting()));
            List<SysUser> users = userIds.isEmpty()
                    ? Collections.emptyList()
                    : sysUserMapper.selectBatchIds(userIds);
            Map<Long, SysUser> userMap = users.stream()
                    .collect(Collectors.toMap(SysUser::getId, u -> u));

            List<Map<String, Object>> topUsers = userSessionCount.entrySet().stream()
                    .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> {
                        SysUser u = userMap.get(e.getKey());
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("userId", e.getKey());
                        m.put("nickname", u != null ? u.getNickname() : "未知用户");
                        m.put("sessionCount", e.getValue());
                        return m;
                    }).collect(Collectors.toList());

            // 高频关键词 Top 10
            List<String> userMessages = allMessages.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .map(ChatMessage::getContent)
                    .collect(Collectors.toList());
            List<Map<String, Object>> topKeywords = extractTopKeywords(userMessages, 10);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("timeRange", timeRange);
            result.put("startTime", startTime.toString());
            result.put("endTime", endTime.toString());
            result.put("totalSessions", sessions.size());
            result.put("totalUsers", userIds.size());
            result.put("totalUserMessages", userMessageCount);
            result.put("totalAssistantMessages", assistantMessageCount);
            result.put("feedbackLikes", likeCount);
            result.put("feedbackDislikes", dislikeCount);
            result.put("satisfactionRate",
                    (likeCount + dislikeCount) == 0 ? null
                            : String.format("%.1f%%",
                            likeCount * 100.0 / (likeCount + dislikeCount)));
            result.put("topUsers", topUsers);
            result.put("topKeywords", topKeywords);

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("analyzeUsageStats 失败", e);
            return errorJson(e.getMessage());
        }
    }

    // ==================== 工具 4：触发学习任务 ====================

    @Tool(
            name = "triggerKnowledgeLearning",
            description = """
                    触发知识库学习任务。scope 取值：
                      - "all_unlearned"：学习全部未学习的文档和视频
                      - "doc_{id}"：学习指定 ID 的文档（例如 doc_3）
                      - "video_{id}"：学习指定 ID 的视频（例如 video_5）
                    任务异步执行，工具立即返回"已触发"，学习结果需后续查询。
                    仅管理员可用。
                    """
    )
    public String triggerKnowledgeLearning(
            @ToolParam(description = "学习范围：all_unlearned / doc_{id} / video_{id}")
            String scope
    ) {
        try {
            log.info("[MCP Tool] triggerKnowledgeLearning scope={}", scope);

            if ("all_unlearned".equals(scope)) {
                return triggerAllUnlearned();
            }
            if (scope != null && scope.startsWith("doc_")) {
                long docId = Long.parseLong(scope.substring(4));
                return mainAppClient.triggerDocumentLearning(docId);
            }
            if (scope != null && scope.startsWith("video_")) {
                long videoId = Long.parseLong(scope.substring(6));
                return mainAppClient.triggerVideoLearning(videoId);
            }

            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "message", "未知的 scope: " + scope + "，支持 all_unlearned / doc_{id} / video_{id}"
            ));
        } catch (Exception e) {
            log.error("triggerKnowledgeLearning 失败 scope={}", scope, e);
            return errorJson(e.getMessage());
        }
    }

    private String triggerAllUnlearned() throws Exception {
        List<FeatureDocument> unlearnedDocs = featureDocumentMapper.selectList(
                new LambdaQueryWrapper<FeatureDocument>()
                        .eq(FeatureDocument::getDeleted, 0)
                        .eq(FeatureDocument::getVectorized, 0)
        );
        List<VideoDocument> unlearnedVideos = videoDocumentMapper.selectList(
                new LambdaQueryWrapper<VideoDocument>()
                        .eq(VideoDocument::getDeleted, 0)
                        .eq(VideoDocument::getLearnStatus, 0)
        );

        if (unlearnedDocs.isEmpty() && unlearnedVideos.isEmpty()) {
            return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "message", "当前没有未学习的文档或视频"
            ));
        }

        int triggered = 0;
        for (FeatureDocument d : unlearnedDocs) {
            mainAppClient.triggerDocumentLearning(d.getId());
            triggered++;
        }
        for (VideoDocument v : unlearnedVideos) {
            mainAppClient.triggerVideoLearning(v.getId());
            triggered++;
        }

        return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "triggeredCount", triggered,
                "docCount", unlearnedDocs.size(),
                "videoCount", unlearnedVideos.size(),
                "message", "已触发 " + triggered + " 个学习任务，正在后台执行"
        ));
    }

    // ==================== 辅助方法 ====================

    private String mapVideoStatus(Integer learnStatus) {
        if (learnStatus == null) return "unknown";
        return switch (learnStatus) {
            case 0 -> "unlearned";
            case 1 -> "learning";
            case 2 -> "learned";
            case 3 -> "failed";
            default -> "unknown";
        };
    }

    private LocalDateTime[] parseTimeRange(String timeRange) {
        LocalDate today = LocalDate.now();
        LocalDateTime start;
        LocalDateTime end = LocalDateTime.now();

        switch (timeRange == null ? "this_week" : timeRange) {
            case "last_week" -> {
                LocalDate mondayThisWeek = today.with(DayOfWeek.MONDAY);
                start = mondayThisWeek.minusWeeks(1).atStartOfDay();
                end = mondayThisWeek.atStartOfDay();
            }
            case "this_month" -> start = today.withDayOfMonth(1).atStartOfDay();
            case "last_30_days" -> start = today.minusDays(30).atStartOfDay();
            default -> start = today.with(DayOfWeek.MONDAY).atStartOfDay(); // this_week
        }
        return new LocalDateTime[]{start, end};
    }

    private List<Map<String, Object>> extractTopKeywords(List<String> texts, int topN) {
        Map<String, Integer> freq = new HashMap<>();
        for (String text : texts) {
            if (text == null) continue;
            for (String token : text.split("[\\s,.;:!?，。；：！？、\"'()\\[\\]【】]+")) {
                String t = token.trim().toLowerCase();
                if (t.length() < 2 || STOP_WORDS.contains(t)) continue;
                if (t.matches("\\d+")) continue;
                freq.merge(t, 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("keyword", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                }).collect(Collectors.toList());
    }

    private String errorJson(String msg) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "message", msg == null ? "未知错误" : msg
            ));
        } catch (Exception ex) {
            return "{\"success\":false,\"message\":\"error\"}";
        }
    }
}