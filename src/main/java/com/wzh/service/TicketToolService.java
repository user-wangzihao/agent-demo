package com.wzh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.entity.ChatMessage;
import com.wzh.mapper.ChatMessageMapper;
import lombok.Data;
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
 * 提供两个工具函数，供 AgentService 的 Function Calling 使用
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

    // ==================== 工具定义（供 Function Calling 使用） ====================

    /**
     * 返回所有可用工具的 JSON Schema 描述
     * 模型会根据这些描述判断何时调用哪个工具
     */
    public static final String TOOLS_SCHEMA = """
            [
              {
                "type": "function",
                "function": {
                  "name": "submitTicket",
                  "description": "当用户明确要求转交技术人员处理、或者当前问题超出知识库范围无法回答时，调用此工具创建工单。调用前需向用户确认，获得同意后再调用。",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "title": {
                        "type": "string",
                        "description": "工单标题，用一句话概括用户的核心问题，不超过50字"
                      },
                      "description": {
                        "type": "string",
                        "description": "问题详细描述，包括：用户描述的现象、已尝试的操作、期望的结果"
                      },
                      "priority": {
                        "type": "string",
                        "enum": ["LOW", "NORMAL", "HIGH", "URGENT"],
                        "description": "优先级：LOW-低，NORMAL-普通，HIGH-高，URGENT-紧急。根据问题紧迫程度判断"
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
                  "description": "查询已提交工单的处理状态和结果。当用户询问工单进度、处理结果时调用。",
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
              }
            ]
            """;

    // ==================== 工具执行 ====================

    /**
     * 执行 submitTicket 工具
     * 将当前会话历史打包提交到工单系统
     *
     * @param sessionId   当前会话ID（用于拉取对话历史）
     * @param userId      用户ID
     * @param userName    用户昵称
     * @param title       工单标题（模型生成）
     * @param description 问题描述（模型生成）
     * @param priority    优先级（模型判断）
     * @return 工单系统返回的结果字符串（传回给模型）
     */
    public String submitTicket(Long sessionId, String userId, String userName,
                               String title, String description, String priority) {
        try {
            // 1. 拉取当前会话的对话历史，打包为 JSON
            String chatHistoryJson = buildChatHistoryJson(sessionId);

            // 2. 构造请求体
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

            // 3. 发送 POST 请求
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

    /**
     * 执行 queryTicketStatus 工具
     *
     * @param ticketNo 工单编号
     * @return 工单状态信息字符串（传回给模型）
     */
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
                JsonNode data = result.get("data");
                return objectMapper.writeValueAsString(data);
            } else {
                return "{\"error\": \"工单不存在或查询失败\"}";
            }

        } catch (Exception e) {
            log.error("查询工单状态失败", e);
            return "{\"error\": \"工单系统暂时无法访问\"}";
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 将会话历史转为 JSON 字符串，传给工单系统
     */
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

    @Data
    public static class ToolCallResult {
        /** 是否需要调用工具 */
        private boolean hasToolCall;
        /** 工具名称 */
        private String toolName;
        /** 工具调用ID */
        private String toolCallId;
        /** 工具参数 */
        private JsonNode arguments;
        /** 如果不需要工具调用，模型直接返回的文本 */
        private String directContent;
    }
}