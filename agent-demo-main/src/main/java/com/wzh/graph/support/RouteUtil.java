package com.wzh.graph.support;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.wzh.enums.Intent;
import com.wzh.graph.core.GraphStateKeys;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Graph 路由判定 + state 安全解码工具类.
 *
 * <p><b>职责</b>: 集中所有 conditionalEdge 的判定逻辑, 以及对 Spring AI Alibaba Graph
 * 1.1.2 state 序列化怪行为的解码兼容.</p>
 *
 * <p><b>分流逻辑</b>:
 * <pre>
 *   intent 之后 (routeAfterIntent):
 *     1. CHITCHAT             → chitchat_answer  (短路, 不走 RAG)
 *     2. ADMIN_COMMAND + admin → admin_agent     (短路, 不走 RAG; 第六刀 B5-b-1)
 *     3. 其他                 → feature_resolve  (进 RAG 链路; 含非 admin 的 admin_command 降级)
 *
 *   merger 之后 (routeAfterMerger):
 *     1. ticket_intent (query 匹配工单关键词) → ticket_agent
 *     2. 其他                                  → knowledge_answer
 * </pre>
 *
 * <p>注意: routeAfterMerger 不再判定 admin —— admin 流量在 intent 阶段就已短路出去,
 * 不会走到 merger.</p>
 *
 * <h2>state 安全解码 (第六刀 B5-b-1 引入)</h2>
 *
 * <p>Spring AI Alibaba Graph 1.1.2 把节点 put 进 partial 的对象做了序列化处理.
 * 经过框架的写-读周期后, 同一个值在 state 里可能呈现为 3 种形态:
 * <ul>
 *   <li>枚举 → {@code ArrayList[className, code]}<br>
 *       例如 {@code Intent.CHITCHAT} 取出可能是 {@code ["com.wzh.enums.Intent", "chitchat"]}</li>
 *   <li>String → 多数情况下仍是 String, 但 MATCHED_FEATURE 等被观察到也会包装为
 *       {@code ArrayList[_, value]}</li>
 *   <li>原对象 → 直接拿到原 Intent / String</li>
 * </ul>
 *
 * <p>直接调 {@code state.value(KEY, Intent.class)} 时, 框架做类型校验,
 * raw 是 ArrayList 就匹配失败返回 empty Optional, 调用方走 {@code orElse(DEFAULT)} 兜底 —
 * 这是一个静默 fallback, 表面看不出问题但 intent 一直是 DEFAULT, 导致:
 * <ol>
 *   <li>routeAfterIntent 里的 chitchat 短路从未生效</li>
 *   <li>IntentBoostUtil 的 chunk_type 加权从未生效</li>
 *   <li>SystemPromptBuilder 的意图风格模板从未生效</li>
 * </ol>
 *
 * <p>本工具类的 {@link #safeIntent(OverAllState)} / {@link #safeString(OverAllState, String, String)}
 * 是统一解码入口, 路由层和节点层都应该用它取代裸 {@code state.value(K, Class)} 调用.</p>
 *
 * @author wzh
 * @since 2026-05-12 (3.B); B5-b-1 重写 (2026-05-19): 加 safeIntent/safeString, 退役 ADMIN_META_PATTERN
 */
@Slf4j
public final class RouteUtil {

    private RouteUtil() {}

    /** 工单意图关键词. */
    private static final Pattern TICKET_PATTERN = Pattern.compile(
            ".*(转人工|转给技术|提.*工单|提交工单|人工处理|联系.{0,4}客服|工单号|工单状态|TK-\\d+).*"
    );

    // ==================== 路由判定 ====================

    /**
     * 是否为 chitchat 短路 (intent → chitchat_answer 分支).
     *
     * <p>复用 {@link Intent#isShortCircuit()}, 保持单一事实源.</p>
     */
    public static boolean isChitchat(Intent intent) {
        return intent != null && intent.isShortCircuit();
    }

    /**
     * 是否为管理员指令短路 (intent → admin_agent 分支).
     *
     * <p><b>判定规则</b>: intent == ADMIN_COMMAND 且 userRole == "admin".</p>
     *
     * <p><b>非 admin 降级</b>: 普通用户即使被分类为 ADMIN_COMMAND (LLM 误判或者
     * 用户在试探权限), 此方法返回 false, fall through 到正常 RAG 链路, 让模型按业务流回答.
     * 不直接拒绝是为了对 LLM 分类抖动友好.</p>
     *
     * <p><b>真正的权限边界</b>: 由 ChatClient 工具集隔离 (Batch 2 IoC 物理保证) 兜底 —
     * 即使误路由进 admin_agent, 也只是用 admin 的 ChatClient 回答 (没有 submitTicket 工具),
     * 但实际上由本方法已经隔离, 不会发生.</p>
     */
    public static boolean isAdminCommand(Intent intent, String userRole) {
        return intent != null && intent.isAdminCommand() && "admin".equals(userRole);
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

    // ==================== state 安全解码 ====================

    /**
     * 从 state 安全读取 Intent. 永不返回 null, 失败一律降级为 {@link Intent#DEFAULT}.
     *
     * <p>用于取代裸调用 {@code state.value(GraphStateKeys.INTENT, Intent.class).orElse(Intent.DEFAULT)},
     * 后者在 Graph 1.1.2 下因 ArrayList 包装会静默 fallback 到 DEFAULT.</p>
     */
    public static Intent safeIntent(OverAllState state) {
        Object raw = state.value(GraphStateKeys.INTENT).orElse(null);
        Intent decoded = decodeIntent(raw);
        return decoded == null ? Intent.DEFAULT : decoded;
    }

    /**
     * 从 state 安全读取 String 字段. defaultValue 用于解码失败 / 字段缺失时的兜底.
     *
     * <p>用于取代裸调用 {@code state.value(KEY, String.class).orElse(default)}.</p>
     */
    public static String safeString(OverAllState state, String key, String defaultValue) {
        Object raw = state.value(key).orElse(null);
        String decoded = decodeString(raw);
        return decoded == null ? defaultValue : decoded;
    }

    /**
     * 反序列化从 state 取出的 Intent. 兼容 3 种形态:
     * <ul>
     *   <li>原 Intent 实例 → 直接返回</li>
     *   <li>{@code ArrayList[_, code]} 或 {@code ArrayList[code]} → 按 code 字符串解析</li>
     *   <li>String code → 按 code 字符串解析</li>
     * </ul>
     *
     * <p>未知类型 / 无法解析 → 返回 null (调用方决定降级行为).</p>
     */
    public static Intent decodeIntent(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Intent i) return i;
        if (raw instanceof List<?> list && !list.isEmpty()) {
            // 优先按 [_, code] 的 code 位置解析; 失败再尝试 [code] 单元素
            Object codeCandidate = list.size() > 1 ? list.get(1) : list.get(0);
            if (codeCandidate instanceof String code) {
                return Intent.fromCodeOrDefault(code);
            }
        }
        if (raw instanceof String code) {
            return Intent.fromCodeOrDefault(code);
        }
        log.warn("[RouteUtil#decodeIntent] 无法解析 raw 类型={} 值={}", raw.getClass(), raw);
        return null;
    }

    /**
     * 反序列化从 state 取出的 String. 兼容 2 种形态:
     * <ul>
     *   <li>原 String → 直接返回</li>
     *   <li>{@code ArrayList[_, value]} 或 {@code ArrayList[value]} → 取 value 位置</li>
     * </ul>
     *
     * <p>其他类型 → 调用 toString() (兜底, 但理论上不应发生; 至少不返回 null).
     * null 输入 → null 输出.</p>
     */
    public static String decodeString(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return s;
        if (raw instanceof List<?> list && !list.isEmpty()) {
            Object candidate = list.size() > 1 ? list.get(1) : list.get(0);
            if (candidate instanceof String s) return s;
        }
        return raw.toString();
    }
}