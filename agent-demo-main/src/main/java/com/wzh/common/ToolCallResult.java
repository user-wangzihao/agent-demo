package com.wzh.common;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

// ==================== 工具调用结果解析 ====================
@Data
public class ToolCallResult {
    private boolean hasToolCall;
    private String toolName;
    private String toolCallId;
    private JsonNode arguments;
    private String directContent;
}