package com.wzh.entity.dto;

import lombok.Data;

@Data
public class FeedbackRequest {
    /** 消息ID */
    private Long messageId;

    /** 评分: 1=点赞, -1=点踩 */
    private Integer rating;

    /** 反馈原因（点踩时可选填写） */
    private String reason;
}