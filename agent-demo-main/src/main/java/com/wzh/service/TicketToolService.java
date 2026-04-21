package com.wzh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工单工具服务 —— 封装对 TicketSystem 的 API 调用
 * TOOLS_SCHEMA 同时包含全部 5 个工具的定义
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketToolService {

    private final ObjectMapper objectMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Value("${ticket-system.base-url:http://localhost:8081}")
    private String ticketBaseUrl;

    @Value("${ticket-system.api-key:agent-demo-secret-key}")
    private String ticketApiKey;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ==================== 工具 Schema（全部 5 个工具）====================

    public static final String TOOLS_SCHEMA = """
            [
              {
                "type": "function",
                "function": {
                  "name": "submitTicket",
                  "description": "当用户明确要求转交技术人员处理、或者当前问题超出知识库范围无法回答时，调用此工具创建工单。调用前需向用户确认。",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "title": {
                        "type": "string",
                        "description": "工单标题，一句话概括用户的核心问题，不超过50字"
                      },
                      "description": {
                        "type": "string",
                        "description": "问题详细描述，包括：用户描述的现象、已尝试的操作、期望的结果"
                      },
                      "priority": {
                        "type": "string",
                        "enum": ["LOW", "NORMAL", "HIGH", "URGENT"],
                        "description": "优先级：LOW-低，NORMAL-普通，HIGH-高，URGENT-紧急"
                      }
                    },
                    "required": ["title", "description", "priority"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "queryTicketStatus",
                  "description": "查询已提交工单的处理状态和结果。当用户询问工单进度或处理结果时调用。",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "ticketNo": {
                        "type": "string",
                        "description": "工单编号，格式如 TK-20260401-001"
                      }
                    },
                    "required": ["ticketNo"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "listDocumentStatus",
                  "description": "查询知识库中所有功能文档和视频的学习状态，包括已学习、未学习、学习失败等。管理员询问学习情况、哪些文档已学习或未学习时调用。",
                  "parameters": {
                    "type": "object",
                    "properties": {},
                    "required": []
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "triggerKnowledgeLearning",
                  "description": "触发知识库学习任务。管理员指示学习未学习的文档或视频时调用。学习任务在后台异步执行，调用后立即返回，不需要等待完成。",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "scope": {
                        "type": "string",
                        "description": "学习范围：'all_unlearned'=学习全部未学习内容；'doc_{id}'=学习指定文档（如 doc_5）；'video_{id}'=学习指定视频（如 video_3）"
                      }
                    },
                    "required": ["scope"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "analyzeUsageStats",
                  "description": "统计分析用户的使用情况，包括对话量、活跃用户、高频关键词、反馈满意度等。管理员询问使用统计或数据分析时调用。",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "timeRange": {
                        "type": "string",
                        "enum": ["this_week", "last_week", "this_month", "last_30_days"],
                        "description": "统计时间范围：this_week-本周，last_week-上周，this_month-本月，last_30_days-近30天"
                      }
                    },
                    "required": ["timeRange"]
                  }
                }
              }
            ]
            """;

    // ==================== 工具执行：submitTicket ====================

    public String submitTicket(Long sessionId, String userId, String userName,
                               String title, String description, String priority) {
        try {
            String chatHistoryJson = buildChatHistoryJson(sessionId);
            var requestBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
                put("source", "AgentDemo");
                put("userId", userId);
                put("userName", userName);
                put("title", title);
                put("description", description);
                put("chatHistory", chatHistoryJson);
                put("agentSessionId", String.valueOf(sessionId));
                put("priority", priority);
            }});

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ticketBaseUrl + "/api/ticket/create"))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", ticketApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(response.body());

            if (result.get("code").asInt() == 200) {
                JsonNode data = result.get("data");
                String ticketNo = data.get("ticketNo").asText();
                log.info("工单创建成功: {}", ticketNo);
                return String.format(
                        "{\"success\": true, \"ticketNo\": \"%s\", \"message\": \"%s\"}",
                        ticketNo, data.get("message").asText()
                );
            } else {
                log.error("工单创建失败: {}", result);
                return "{\"success\": false, \"message\": \"工单创建失败，请稍后重试\"}";
            }
        } catch (Exception e) {
            log.error("调用工单系统失败", e);
            return "{\"success\": false, \"message\": \"工单系统暂时无法访问，请联系技术支持\"}";
        }
    }

    // ==================== 工具执行：queryTicketStatus ====================

    public String queryTicketStatus(String ticketNo) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ticketBaseUrl + "/api/ticket/status/" + ticketNo))
                    .header("X-Api-Key", ticketApiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(response.body());

            if (result.get("code").asInt() == 200) {
                return objectMapper.writeValueAsString(result.get("data"));
            } else {
                return "{\"error\": \"工单不存在或查询失败\"}";
            }
        } catch (Exception e) {
            log.error("查询工单状态失败", e);
            return "{\"error\": \"工单系统暂时无法访问\"}";
        }
    }

    // ==================== 私有：构建对话历史 ====================

    private String buildChatHistoryJson(Long sessionId) {
        try {
            List<ChatMessage> messages = chatMessageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, sessionId)
                            .orderByAsc(ChatMessage::getCreateTime));
            List<java.util.Map<String, String>> history = messages.stream()
                    .map(m -> new java.util.HashMap<String, String>() {{
                        put("role", m.getRole());
                        put("content", m.getContent());
                    }})
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(history);
        } catch (Exception e) {
            log.warn("构建对话历史失败", e);
            return "[]";
        }
    }

    // ==================== 工具调用结果解析 ====================

    /*@Data
    public static class ToolCallResult {
        private boolean hasToolCall;
        private String toolName;
        private String toolCallId;
        private JsonNode arguments;
        private String directContent;
    }*/
}