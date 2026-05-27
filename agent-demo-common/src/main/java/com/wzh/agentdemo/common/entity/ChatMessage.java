package com.wzh.agentdemo.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** user / assistant */
    private String role;

    private String content;

    /** 本轮匹配到的功能名:chitchat=闲聊,NULL=未匹配,其他=具体功能名 (老数据可能为 "Chit") */
    private String featureName;

    /** JSON: 关联图片URL列表（AI回答附带的参考图片） */
    private String relatedImages;

    /** JSON: 引用来源 */
    private String sources;

    /** JSON: 用户上传的图片URL列表（用户提问时附带的截图） */
    private String userImages;

    /** 反馈评分: 1-点赞 -1-点踩 null-未反馈 */
    private Integer feedbackRating;

    /** 反馈原因（点踩时填写） */
    private String feedbackReason;

    /** 反馈时间 */
    private LocalDateTime feedbackTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * B4: FAQ 命中标记.
     * - assistant 行: sources 列表至少有一条 chunkType="FAQ" 时为 TRUE
     * - user 行: 恒为 FALSE (DB DEFAULT), 不主动维护; 统计走 role='assistant'
     *
     * <p>大屏 KPI 卡 #3 (FAQ 命中率) 的数据基础.</p>
     */
    @TableField("faq_hit")
    private Boolean faqHit;

    /**
     * B6 (第3刀语义缓存): 命中的语义缓存 cache_key.
     * <ul>
     *   <li>assistant 行: L1/L2 命中时写入对应 cacheKey, 未命中为 NULL</li>
     *   <li>user 行: 恒为 NULL, 不维护</li>
     * </ul>
     *
     * <p>用途:</p>
     * <ol>
     *   <li>负反馈三入口 (点踩 / 重新生成 / 提交工单) 反查对应缓存累加 feedback_score</li>
     *   <li>事后归因分析: 缓存命中的回答最终是否被用户接受</li>
     * </ol>
     */
    @TableField("cache_key")
    private String cacheKey;

    /**
     * B3-a (第3刀语义缓存): 本次回答的缓存命中层级.
     * <ul>
     *   <li>L1: 精确命中</li>
     *   <li>L2: 语义命中</li>
     *   <li>NULL: 未命中 (可能是新写入了缓存, 也可能是不缓存的场景如 chitchat/admin/ticket/faqHit)</li>
     * </ul>
     * 配合 cacheKey 使用: cacheKey != null && cacheHitLayer != null → 命中消费;
     * cacheKey != null && cacheHitLayer == null → 新生成并写回缓存.
     */
    @TableField("cache_hit_layer")
    private String cacheHitLayer;

    /**
     * B5 (第3刀语义缓存负反馈入口3): 用户主动点"提交工单"按钮后的工单号.
     *
     * <p><b>三态取值</b>:
     * <ul>
     *   <li>{@code null}: 未提单 / 提单失败 (前端按钮可点)</li>
     *   <li>{@code "SUBMITTING"}: 提单中占位 (前端按钮置灰显示"提交中...";
     *       由 Controller 在按钮端点入口处写入, MCP 回填真实 ticketNo 时覆盖,
     *       handleDone 兜底检查若仍是占位则回滚 null)</li>
     *   <li>{@code "TK-yyyyMMdd-NNNN"}: 工单成功 (前端按钮置灰显示"已提单 TK-...";
     *       由 MCP 调 TicketSystem 成功后, 同步回调 main 的 /internal/ticket/callback 写入)</li>
     * </ul>
     *
     * <p><b>仅 assistant 消息有意义</b>: 用户语义上是"对某条 AI 答复不满意 → 转工单",
     * 工单号挂在被吐槽的那条 assistant 消息上, user 消息恒为 null.</p>
     *
     * <p><b>设计动机</b>: 通过 MCP 端的事实回调直接落库, 不依赖 LLM 答复文本里的工单号正则提取.
     * 这是因为工单成功的事实 (HTTP 200 + ticketNo 非空) 在 TicketSystem 响应那一刻就已确定,
     * 让 LLM 答复决定数据落库相当于把"系统真相"交给"语言模型解读", 方向反了.</p>
     */
    @TableField("submitted_ticket_id")
    private String submittedTicketId;

}