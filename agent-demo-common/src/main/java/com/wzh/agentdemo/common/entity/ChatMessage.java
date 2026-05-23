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

}