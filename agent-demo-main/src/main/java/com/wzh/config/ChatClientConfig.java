package com.wzh.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 配置。
 *
 * 注入 MCP Client 自动发现的 ToolCallbackProvider（即远程 MCP Server 暴露的工具），
 * 构建一个带工具能力的 ChatClient Bean。
 *
 * 使用方式：在需要工具调用的地方注入这个 ChatClient，调用 .prompt()...tools()... 即可。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 带 MCP 工具能力的 ChatClient。
     *
     * ChatModel 由 spring-ai-alibaba-starter-dashscope 自动提供（对应 DashScopeChatModel）。
     * ToolCallbackProvider 由 spring-ai-starter-mcp-client 自动装配，内部封装了所有已连接 MCP Server 的工具。
     */
    @Bean
    public ChatClient mcpChatClient(ChatModel chatModel, ToolCallbackProvider toolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
    }
}