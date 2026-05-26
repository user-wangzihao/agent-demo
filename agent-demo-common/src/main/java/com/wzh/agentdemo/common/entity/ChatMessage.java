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

}