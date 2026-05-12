package com.wzh.graph.support;

import com.wzh.enums.Intent;

import java.util.regex.Pattern;

/**
 * Graph 路由判定工具类 (3.B 引入).
 *
 * <p><b>职责</b>: 集中所有 conditionalEdge 的判定逻辑, 便于单测和迭代.</p>
 *
 * <p><b>设计哲学</b>: 3.B 阶段使用关键词正则 + 角色判定 (做法 X).
 * 第六刀升级到做法 Y 时, 这里会被 router agent 替代或与之配合.</p>
 *
 * <p><b>分流优先级</b> (merger 之后):
 * <pre>
 *   1. admin_meta_query  (要求 userRole=admin 且 query 匹配元数据关键词)
 *   2. ticket_intent     (query 匹配工单关键词)
 *   3. knowledge_answer  (默认兜底)
 * </pre></p>
 *
 * @author wzh
 * @since 2026-05-12
 */
public final class RouteUtil {

    private RouteUtil() {}

    // 管理员元数据查询关键词
    private static final Pattern ADMIN_META_PATTERN = Pattern.compile(
            ".*(有哪些文档|未学习|学习一下|学习所有|重新学习|学习情况|知识库状态" +
                    "|文档学习|使用情况|本周.*统计|本月.*统计|大屏数据|用户满意度|分析).*"
    );

    // 工单意图关键词
    private static final Pattern TICKET_PATTERN = Pattern.compile(
            ".*(转人工|转给技术|提.*工单|提交工单|人工处理|联系客服|工单号|工单状态|TK-\\d+).*"
    );

    /**
     * 是否为 chitchat 短路 (intent → chitchat_answer 分支).
     *
     * <p>复用 {@link Intent#isShortCircuit()}, 保持单一事实源.</p>
     */
    public static boolean isChitchat(Intent intent) {
        return intent != null && intent.isShortCircuit();
    }

    /**
     * 是否为管理员元数据查询 (merger → admin_agent 分支).
     *
     * <p><b>判定规则</b>: userRole=admin 且 query 匹配元数据关键词.</p>
     *
     * <p><b>注意</b>: 非 admin 用户即使 query 含关键词, 也不进 admin_agent
     * (做法 X 阶段的角色隔离仍由这条规则保证).</p>
     */
    public static boolean isAdminMetaQuery(String query, String userRole) {
        if (!"admin".equals(userRole)) return false;
        if (query == null || query.isBlank()) return false;
        return ADMIN_META_PATTERN.matcher(query).matches();
    }

    /**
     * 是否为工单意图 (merger → ticket_agent 分支).
     *
     * <p><b>判定规则</b>: query 匹配工单关键词. 不强制 intent, 因为
     * "转人工"语义跨意图存在 (用户可能在 troubleshoot 后才说转人工).</p>
     */
    public static boolean isTicketIntent(String query, Intent intent) {
        if (query == null || query.isBlank()) return false;
        return TICKET_PATTERN.matcher(query).matches();
    }
}