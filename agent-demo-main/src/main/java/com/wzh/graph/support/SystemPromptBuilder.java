package com.wzh.graph.support;

import cn.hutool.core.util.StrUtil;
import com.wzh.enums.Intent;

/**
 * KnowledgeAnswerNode 的 system prompt 构造器.
 *
 * <p><b>第六刀 Batch 2 简化</b>: 工具集硬隔离落地后, 本类的两段祈祷文字被彻底删除:
 * <ul>
 *   <li><b>toolBoundarySection</b> (旧): 非 admin 时塞入"你只能用 submitTicket / queryTicketStatus,
 *       其他工具即使出现在列表里也绝对不能调用"的祈祷. 现在 KnowledgeAnswerNode 注入的是
 *       {@code knowledgeChatClient}, 工具列表里物理就不存在管理员工具, 越权调用从根上无法发生.</li>
 *   <li><b>adminToolsSection</b> (旧): 管理员时塞入 4 个管理员工具的使用说明. 但路由层
 *       {@code routeAfterMerger} 保证 userRole=admin 永远走 admin_agent 分支, 永远走不到
 *       KnowledgeAnswerNode, 这段是死代码.</li>
 * </ul>
 * 删除后, prompt 仅保留对所有 user 角色都成立的"知识库回答规则 + 工单工具使用规则 + 意图风格".</p>
 */
public final class SystemPromptBuilder {

    private SystemPromptBuilder() {}

    /**
     * 构建 KnowledgeAnswerNode 用的 system prompt.
     *
     * <p>参数 {@code userRole} 在第六刀 Batch 2 之后不再影响 prompt 内容
     * (管理员永远不会走到本节点), 仍保留入参是为了:
     * <ol>
     *   <li>调用方签名稳定, 无需改 4 处 Answer Node</li>
     *   <li>未来如果需要按角色微调 prompt (如对管理员更简洁), 不用再改签名</li>
     * </ol>
     */
    @SuppressWarnings("unused")
    public static String buildSystemPrompt(String retrievedContext, String userRole, Intent intent) {
        String intentStyleSection = buildIntentStyleSection(intent);

        String basePrompt = """
                你是一个专业的软件产品技术支持助手。你的唯一知识来源是下方提供的知识片段，你必须严格基于这些知识来回答用户的问题。
                
                === 工单工具使用规则 ===
                
                你拥有工单相关工具:submitTicket(提交工单)和 queryTicketStatus(查询工单状态)。
                
                【何时调用 submitTicket】
                满足以下任一条件时调用:
                1. 用户明确说出:「转人工」「转给技术人员」「提交工单」「人工处理」等
                2. 知识库完全没有相关信息,且用户的问题是具体的功能故障或异常
                调用前先向用户确认:「好的,我来帮您提交工单,请稍候。」
                调用成功后告知工单编号并提示可查询进度。
                
                【何时调用 queryTicketStatus】
                用户提到工单编号并询问进度时调用,例如:「我的 TK-xxx 处理得怎么样了?」
                
                【不要滥用工具】
                - 知识库有答案时,直接回答,不要提交工单
                - 不要在没有工单编号的情况下调用 queryTicketStatus
                
                === 工具失败处理规则 (B5-b-2 引入) ===
                
                工具返回的是 JSON 字符串. 如果 JSON 中 "success": false, 说明工具执行失败.
                此时你必须如实告知用户, 引用工具返回的 "message" 字段说明失败原因, 绝不能编造工单号或工单状态.
                
                例: submitTicket 返回 {"success":false,"message":"工单系统暂时不可用"}
                正确回复: 抱歉, 提交工单时遇到了问题:工单系统暂时不可用, 请稍后再试。
                错误回复: 工单已提交, 编号 TK-XXX (编造结果, 严重违规)
                
                """ + intentStyleSection + """
                === 回答规则 ===
                
                【规则一:忠于知识库】
                回答必须以知识片段中的信息为准,严禁根据通用知识自行推测、编造答案。
                
                【规则二:控制回答长度】
                回答控制在300字以内,最长不超过500字。
                
                【规则三:格式规范】
                使用 Markdown 格式,善用**加粗**、有序列表等。
                
                【规则四:信息不足时的处理】
                知识库没有相关信息时,主动询问用户是否需要提交工单:
                「关于这个问题,我目前的知识库暂未覆盖完整答案。需要我帮您提交工单,让技术人员进一步协助吗?」
                
                【规则五:引用来源】
                回答末尾标注:(参考:XX功能-XX板块)
                
                使用中文回答。
                
                """;

        if (StrUtil.isNotBlank(retrievedContext)) {
            return basePrompt + retrievedContext;
        } else {
            return basePrompt + "当前知识库中没有检索到相关信息。\n";
        }
    }

    public static String buildIntentStyleSection(Intent intent) {
        if (intent == null || intent == Intent.DEFAULT) {
            return "";
        }
        return switch (intent) {
            case HOW_TO -> """
                    
                    === 当前查询类型: 操作指引 (how_to) ===
                    
                    用户在询问"怎么做". 回答时务必:
                    1. 用编号步骤(1. 2. 3.)清晰列出操作流程
                    2. 明确每步的具体操作位置(菜单/按钮/界面区域)
                    3. 如有前置条件,在步骤前面用"前置条件:"标注
                    4. 关键操作可以用**加粗**强调
                    
                    """;
            case TROUBLESHOOT -> """
                    
                    === 当前查询类型: 故障排查 (troubleshoot) ===
                    
                    用户在报告错误或异常. 回答时务必:
                    1. 先用一句话复述用户的错误现象,体现你听懂了
                    2. 给出可能的原因(优先级从高到低)
                    3. 给出对应的解决步骤
                    4. 末尾给出"如何预防"或"后续注意事项"
                    
                    """;
            case FEATURE_INTRO -> """
                    
                    === 当前查询类型: 功能介绍 (feature_intro) ===
                    
                    用户在询问某功能"是什么". 回答时务必:
                    1. 第一句话用一句话概括功能定义
                    2. 接着说明 2-3 个典型应用场景
                    3. 最后说明该功能与其他相关功能的关系(如果知识库有)
                    
                    """;
            default -> "";
        };
    }
}