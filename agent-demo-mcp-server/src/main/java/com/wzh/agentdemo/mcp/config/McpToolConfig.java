package com.wzh.agentdemo.mcp.config;

import com.wzh.agentdemo.mcp.tool.KnowledgeTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 @Tool 方法注册为 ToolCallbackProvider。
 * 注意：
 * - KnowledgeTools 使用 Spring AI 主库的 @Tool 注解，通过本 Bean 注册。
 * - TicketTools 使用社区的 @McpTool 注解（支持 McpMeta 透传），
 *   由 spring-ai-mcp-annotations 自动扫描注册，不需要在这里声明。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider agentToolCallbacks(KnowledgeTools knowledgeTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeTools)
                .build();
    }
}