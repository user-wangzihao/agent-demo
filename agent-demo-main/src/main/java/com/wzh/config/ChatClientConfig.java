package com.wzh.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Spring AI ChatClient 配置 (第六刀 Batch 2: 工具集硬隔离).
 *
 * <p><b>背景</b>: 第六刀之前, 全应用只有一个 {@code mcpChatClient} bean, 把 MCP Server 暴露的
 * 全部 6 个工具一股脑灌进去, 然后靠 system prompt 里"你只能用 xx 工具""不要调 yy 工具"
 * 这种 <i>祈祷式约束</i> 防越权. 这种做法的根本问题在于:
 * <ul>
 *   <li>权限边界靠模型自觉, 没有任何物理阻断</li>
 *   <li>越权工具仍会出现在工具列表里, 占 prompt token 且诱导模型误调</li>
 *   <li>多个 Node 共享同一个 ChatClient, "工单 Agent / 管理员 Agent" 在工具维度上
 *       和 "知识问答" 没有任何区别, Multi-Agent 架构在实施层面就是个壳子</li>
 * </ul>
 *
 * <p><b>本次改造</b>: 在客户端侧按工具名 filter, 拆成 4 个 ChatClient bean, 每个 bean
 * 只装载本场景需要的工具子集. 这样:
 * <ul>
 *   <li>权限边界从 prompt 下沉到 IoC 容器, 不可绕过</li>
 *   <li>各 Answer Node prompt 里的"工具自觉"段落可以一并删除, 省 token</li>
 *   <li>Multi-Agent 在工具维度真正分立, 而非仅 Node 名字不同</li>
 * </ul>
 *
 * <p><b>工具分配</b>:
 * <pre>
 *   chitchatChatClient    无工具 (闲聊场景, ChitchatAnswerNode)
 *   knowledgeChatClient   submitTicket + queryTicketStatus + retrievalSource (KnowledgeAnswerNode)
 *   ticketChatClient      submitTicket + queryTicketStatus (TicketAgentNode)
 *   adminChatClient       listDocumentStatus + analyzeUsageStats + triggerKnowledgeLearning + retrievalSource (AdminAgentNode)
 * </pre>
 *
 * <p><b>retrievalSource 的归属</b>: 该工具用于追问"刚才的回答来自哪", 用户和管理员都可能问,
 * 故同时挂在 knowledge 和 admin 两个 client 上.</p>
 *
 * <p><b>mcpChatClient (老 bean) 暂留</b>: AgentService 还在引用, 第六刀 Batch 4 删 AgentService
 * 时一并删除. 老 bean 仍持有全部工具, 行为不变.</p>
 */
@Slf4j
@Configuration
public class ChatClientConfig {

    // ==================== 工具名常量 ====================

    /** 用户场景: 工单提交 / 查询 */
    private static final Set<String> USER_TICKET_TOOLS = Set.of(
            "submitTicket", "queryTicketStatus"
    );

    /** 通用: 检索来源溯源 (用户和管理员都可能用) */
    private static final Set<String> RETRIEVAL_SOURCE_TOOLS = Set.of(
            "retrievalSource"
    );

    /** 管理员场景: 文档状态 / 使用统计 / 触发学习 */
    private static final Set<String> ADMIN_TOOLS = Set.of(
            "listDocumentStatus", "analyzeUsageStats", "triggerKnowledgeLearning"
    );

    // ==================== 5 个 ChatClient Bean ====================

    /**
     * 闲聊场景: 不挂任何工具.
     * 即使模型在 prompt 里看到 "tool" 字眼, 也没有可调用的工具实例, 物理阻断.
     */
    @Bean
    public ChatClient chitchatChatClient(ChatModel chatModel) {
        log.info("[ChatClientConfig] 构建 chitchatChatClient (0 个工具)");
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 知识问答场景 (普通用户): submitTicket + queryTicketStatus + retrievalSource.
     * KnowledgeAnswerNode 注入此 client. 用户在知识库无答案时可由模型主动提交工单,
     * 或追问刚才回答的来源.
     */
    @Bean
    public ChatClient knowledgeChatClient(ChatModel chatModel, ToolCallbackProvider provider) {
        Set<String> allowed = union(USER_TICKET_TOOLS, RETRIEVAL_SOURCE_TOOLS);
        ToolCallback[] tools = filterTools(provider, allowed);
        log.info("[ChatClientConfig] 构建 knowledgeChatClient ({} 个工具): {}",
                tools.length, allowed);
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(tools)
                .build();
    }

    /**
     * 工单 Agent 场景: 仅 submitTicket + queryTicketStatus.
     * TicketAgentNode 注入此 client. 工单链路专用, 不容许调用任何知识/管理类工具.
     */
    @Bean
    public ChatClient ticketChatClient(ChatModel chatModel, ToolCallbackProvider provider) {
        ToolCallback[] tools = filterTools(provider, USER_TICKET_TOOLS);
        log.info("[ChatClientConfig] 构建 ticketChatClient ({} 个工具): {}",
                tools.length, USER_TICKET_TOOLS);
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(tools)
                .build();
    }

    /**
     * 管理员 Agent 场景: 4 个管理员工具 + retrievalSource.
     * AdminAgentNode 注入此 client. 不含 submitTicket / queryTicketStatus,
     * 物理阻断管理员误触发工单创建.
     */
    @Bean
    public ChatClient adminChatClient(ChatModel chatModel, ToolCallbackProvider provider) {
        Set<String> allowed = union(ADMIN_TOOLS, RETRIEVAL_SOURCE_TOOLS);
        ToolCallback[] tools = filterTools(provider, allowed);
        log.info("[ChatClientConfig] 构建 adminChatClient ({} 个工具): {}",
                tools.length, allowed);
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(tools)
                .build();
    }

    /**
     * 老 bean, AgentService 还在引用, 第六刀 Batch 4 一起删除.
     * 持有全部工具, 行为兼容旧链路.
     */
    @Bean
    public ChatClient mcpChatClient(ChatModel chatModel, ToolCallbackProvider toolCallbackProvider) {
        log.info("[ChatClientConfig] 构建 mcpChatClient (老 bean, 全部工具, Batch 4 删除)");
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
    }

    // ==================== 内部工具方法 ====================

    /**
     * 从 ToolCallbackProvider 提供的全部 ToolCallback 里, 按工具名 filter 出子集.
     *
     * <p><b>名字匹配策略</b>: Spring AI 1.1.0 起, MCP 客户端拿到的 ToolCallback 实际是
     * {@link SyncMcpToolCallback}, 其 {@code getToolDefinition().name()} 会带 MCP 客户端
     * 前缀 (如 {@code spring_ai_mcp_client_agent_demo_mcp_server_submitTicket}). 因此:
     * <ol>
     *   <li>优先使用 1.1.0 新增的 {@link SyncMcpToolCallback#getOriginalToolName()},
     *       直接拿到不带前缀的原始名 (如 {@code submitTicket})</li>
     *   <li>fallback: 用 {@code name.contains(allowedName)} 兜底, 兼容非 MCP 工具或
     *       未来的接口调整</li>
     * </ol></p>
     */
    private ToolCallback[] filterTools(ToolCallbackProvider provider, Set<String> allowedNames) {
        ToolCallback[] all = provider.getToolCallbacks();
        List<ToolCallback> filtered = Arrays.stream(all)
                .filter(tc -> matches(tc, allowedNames))
                .toList();

        // 健壮性检查: 若期望 N 个工具但实际只匹配到 M (M<N), 打 warn 不抛错
        if (filtered.size() < allowedNames.size()) {
            log.warn("[ChatClientConfig] 工具 filter 不完整: 期望 {} 个 {}, 实际命中 {} 个. " +
                            "可能原因: MCP Server 未启动 / 工具未注册 / 工具名变更.",
                    allowedNames.size(), allowedNames, filtered.size());
        }
        return filtered.toArray(new ToolCallback[0]);
    }

    private boolean matches(ToolCallback tc, Set<String> allowedNames) {
        // 优先用 MCP 1.1.0 提供的原始工具名
        if (tc instanceof SyncMcpToolCallback mcp) {
            return allowedNames.contains(mcp.getOriginalToolName());
        }
        // fallback: 带前缀的 name 包含期望名
        String name = tc.getToolDefinition().name();
        return allowedNames.stream().anyMatch(name::contains);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}