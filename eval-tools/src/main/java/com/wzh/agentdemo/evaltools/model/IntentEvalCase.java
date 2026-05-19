package com.wzh.agentdemo.evaltools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图 / 路由评估的一条 case (Batch 1 引入, 与 {@link EvalCase} 并列存在).
 *
 * <p>对应 {@code eval-set-intent.txt} 中的一个块. 不含 chunk_id / answer 字段,
 * 因为这类评估不关心检索质量, 只关心 query 在分类器和路由层的行为.</p>
 *
 * <p><b>为什么不复用 EvalCase</b>: EvalCase 是 GroundTruthAuditor 使用的检索评估模型,
 * 字段语义和 IntentEvalCase 完全不同 (一个关心 chunk 命中、一个关心枚举值映射).
 * 强行复用会让两边都变成"半空字段对象", 不如各自独立清晰.</p>
 *
 * <p><b>category 取值</b>: 闲聊类 / 管理员指令类 / 工单类 / 兜底类 (与
 * eval-set-intent.txt 的大段落标题对齐).</p>
 *
 * @author wzh
 * @since 2026-05-19 (评估 CI Batch 1)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentEvalCase {

    /** 全局自增 ID (1-based), 由 parser 赋值 */
    private int evalId;

    /** 大类: 闲聊类 / 管理员指令类 / 工单类 / 兜底类 */
    private String category;

    /** 用户原始 query */
    private String query;

    /**
     * 期望意图 code (与 Intent.code 对齐, 如 chitchat / admin_command / how_to /
     * troubleshoot / feature_intro / default).
     * <p>之所以存 String 而非 Intent 枚举: eval-tools 与主应用编译期解耦,
     * 不引入主应用依赖. 评估器对比时按字符串 equals 即可.</p>
     */
    private String expectedIntent;

    /**
     * 期望路由节点名 (与 MainGraphConfig 的 NODE_* 常量字面值对齐,
     * 如 chitchat_answer / admin_agent / feature_resolve / knowledge_answer / ticket_agent).
     * <p>可空: 当 expectedIntent 已足以推断路由时, 标注者可以省略.
     * Batch 4 路由评估会用 (intent + userRole + ticket 关键词) 推算实际路由,
     * 与本字段对比.</p>
     */
    private String expectedRoute;

    /**
     * 模拟用户角色: admin / user.
     * <p>默认为 user. 仅 admin_command 类需要标注 admin 才能验证
     * "admin_command + admin → admin_agent" 路径; 同时也保留一条
     * "admin_command + user → feature_resolve 降级" 的反例.</p>
     */
    @Builder.Default
    private String userRole = "user";
}
