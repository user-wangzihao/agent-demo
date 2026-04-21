package com.wzh.agentdemo.mcp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server 独立应用入口。
 * 端口 8081，暴露 MCP 协议端点：
 *   SSE stream:    GET  /sse
 *   客户端发消息:   POST /mcp/message
 */
@SpringBootApplication
@MapperScan("com.wzh.agentdemo.common.mapper")
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
