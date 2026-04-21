package com.wzh.entity.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    /** 会话ID（为空则新建会话） */
    private Long sessionId;

    /** 用户消息文本 */
    private String message;

    /** 用户上传的图片URL列表（截图提问场景） */
    private List<String> imageUrls;
}