package com.wzh.agentdemo.evaltools.route;

import java.util.regex.Pattern;

/**
 * 路由模拟器 (评估 CI 主线 Batch 4 引入).
 *
 * <p><b>本类是主应用 {@code com.wzh.graph.support.RouteUtil} 和
 * {@code com.wzh.graph.core.MainGraphConfig} 的纯逻辑副本</b>, 用于离线路由评估,
 * 不依赖主应用的 Spring 上下文或 Graph 框架.</p>
 *
 * <h2>⚠️ 与主应用代码同步纪律</h2>
 *
 * <p><b>这是一份 hand-maintained 副本</b>. 如果主应用侧修改了路由规则
 * (TICKET_PATTERN / isChitchat / isAdminCommand / isTicketIntent / routeAfterIntent /
 * routeAfterMerger), 必须同步本类, 否则路由评估会出假阳/假阴.</p>
 *
 * <p><b>同步 checklist</b> (主应用 RouteUtil / MainGraphConfig 变更时逐项核对):
 * <ol>
 *   <li>{@code TICKET_PATTERN} 正则字面值是否一致?</li>
 *   <li>{@link #isChitchat(String)} 判定规则是否一致? (主应用走 Intent.isShortCircuit, 当前只 CHITCHAT)</li>
 *   <li>{@link #isAdminCommand(String, String)} 是否一致? (主应用要求 intent==ADMIN_COMMAND
 *       && userRole=="admin", 注意非 admin 降级到 feature_resolve)</li>
 *   <li>{@link #isTicketIntent(String)} 正则是否一致?</li>
 *   <li>{@link #simulate(String, String, String)} 的分流顺序是否一致?
 *       (chitchat 短路 → admin 短路 → feature_resolve → 进 RAG 后判 ticket → ticket_agent
 *       否则 → knowledge_answer)</li>
 * </ol>
 *
 * <p><b>对应主应用版本</b>: 第六刀 B5-b-1 完结后的状态 (2026-05-19).</p>
 *
 * <h2>路由分流总图 (来自 MainGraphConfig)</h2>
 *
 * <pre>
 *   intent ──(chitchat)─────→ chitchat_answer
 *      │
 *      ├──(admin_command + admin)──→ admin_agent
 *      │
 *      ↓
 *   feature_resolve ──→ doc_retrieve / faq_retrieve ──→ merger
 *      │
 *      ├──(ticket query 命中)──→ ticket_agent
 *      │
 *      └──(默认)──→ knowledge_answer
 * </pre>
 *
 * <p>所有路由的 leaf 节点 5 选 1: chitchat_answer / admin_agent / feature_resolve /
 * ticket_agent / knowledge_answer.</p>
 *
 * <p><b>关于 feature_resolve</b>: 严格来说 feature_resolve 是中间节点而非 leaf, 后面还会
 * 经过 doc_retrieve / faq_retrieve / merger 才走到 knowledge_answer 或 ticket_agent.
 * 但评估集中 {@code admin_command + user role} 降级用例 (eval-set-intent.txt #14) 标的
 * expected_route 就是 feature_resolve, 这是把它当作 "RAG 入口" 的语义标签, 表示
 * "我应该进 RAG 流程, 不应该走捷径", 不要求一直跑到 knowledge_answer 才算正确.
 * 评估集其他 case 标的是端到端 leaf (chitchat_answer / admin_agent / ticket_agent /
 * knowledge_answer), 这是"路由最终把请求带到哪个有响应能力的节点"的语义.
 * 两种语义在 {@link #simulate(String, String, String)} 中通过分支判断各自命中.</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 4)
 */
public final class RouteSimulator {

    private RouteSimulator() {}

    // ==================== 路由出口常量 (与 MainGraphConfig.NODE_* 字面值对齐) ====================

    public static final String CHITCHAT_ANSWER = "chitchat_answer";
    public static final String ADMIN_AGENT = "admin_agent";
    public static final String FEATURE_RESOLVE = "feature_resolve";
    public static final String TICKET_AGENT = "ticket_agent";
    public static final String KNOWLEDGE_ANSWER = "knowledge_answer";

    // ==================== 工单关键词 (对齐主应用 RouteUtil.TICKET_PATTERN) ====================

    /**
     * 工单意图判定正则.
     * <p><b>同步源</b>: {@code com.wzh.graph.support.RouteUtil.TICKET_PATTERN}.</p>
     */
    private static final Pattern TICKET_PATTERN = Pattern.compile(
            ".*(转人工|转给技术|提.*工单|提交工单|人工处理|联系客服|工单号|工单状态|TK-\\d+).*"
    );

    // ==================== 单个判定 (对齐 RouteUtil 同名方法) ====================

    /**
     * intent == "chitchat" 即闲聊短路.
     * <p>主应用是 {@code intent.isShortCircuit()}, 当前 Intent 枚举中只有 CHITCHAT
     * 返回 true. 注意: ADMIN_COMMAND 不在此判定范围, 它有自己的 isAdminCommand.</p>
     */
    public static boolean isChitchat(String intent) {
        return "chitchat".equalsIgnoreCase(intent);
    }

    /**
     * intent == "admin_command" 且 userRole == "admin".
     * <p>非 admin 用户即使被分类为 admin_command, 也降级到 RAG 链路 (feature_resolve).</p>
     */
    public static boolean isAdminCommand(String intent, String userRole) {
        return "admin_command".equalsIgnoreCase(intent) && "admin".equalsIgnoreCase(userRole);
    }

    /**
     * query 命中工单关键词正则.
     * <p>主应用不强制 intent 类型, 因为"转人工"语义跨意图存在 (用户可能在 troubleshoot 后才说转人工).</p>
     */
    public static boolean isTicketIntent(String query) {
        if (query == null || query.isBlank()) return false;
        return TICKET_PATTERN.matcher(query).matches();
    }

    // ==================== 端到端路由模拟 ====================

    /**
     * 给定 intent + query + userRole, 模拟主 Graph 的整条路由链路, 返回最终命中的 leaf 节点名.
     *
     * <p><b>分流顺序</b> (严格对齐 MainGraphConfig.routeAfterIntent + routeAfterMerger):
     * <ol>
     *   <li>chitchat → {@link #CHITCHAT_ANSWER}, 不走 RAG</li>
     *   <li>admin_command + admin → {@link #ADMIN_AGENT}, 不走 RAG</li>
     *   <li>否则进 RAG (feature_resolve → doc_retrieve / faq_retrieve → merger):
     *       <ol>
     *         <li>非 admin 被分类为 admin_command 时, 在评估集语义下视为
     *             {@link #FEATURE_RESOLVE} (RAG 入口标签, 见类注释)</li>
     *         <li>其他业务意图 (how_to / troubleshoot / feature_intro / default) 在 merger 后:
     *             <ul>
     *               <li>query 命中工单关键词 → {@link #TICKET_AGENT}</li>
     *               <li>否则 → {@link #KNOWLEDGE_ANSWER}</li>
     *             </ul>
     *         </li>
     *       </ol>
     *   </li>
     * </ol>
     *
     * @param intent   意图 code (chitchat / admin_command / how_to / ...). null 当作 default 处理.
     * @param query    用户原始 query (用于工单关键词判定)
     * @param userRole "admin" / "user"; null 当作 "user" 处理
     * @return 五个 leaf 节点之一: chitchat_answer / admin_agent / feature_resolve /
     *         ticket_agent / knowledge_answer
     */
    public static String simulate(String intent, String query, String userRole) {
        // Step 1: chitchat 短路 (路由层最高优先级)
        if (isChitchat(intent)) {
            return CHITCHAT_ANSWER;
        }
        // Step 2: admin_command + admin 短路
        if (isAdminCommand(intent, userRole)) {
            return ADMIN_AGENT;
        }
        // Step 3: 非 admin 用户的 admin_command 降级 - 标签为 feature_resolve (RAG 入口)
        // 注意: 这里语义上是"进 RAG 流程"而非"走完整个 RAG 跑到 knowledge_answer".
        // 评估集 #14 就是这条规则: admin_command + user → expected_route=feature_resolve.
        if ("admin_command".equalsIgnoreCase(intent)) {
            return FEATURE_RESOLVE;
        }
        // Step 4: 进 RAG 后, merger 阶段判工单
        if (isTicketIntent(query)) {
            return TICKET_AGENT;
        }
        // Step 5: 默认 knowledge_answer
        return KNOWLEDGE_ANSWER;
    }
}
