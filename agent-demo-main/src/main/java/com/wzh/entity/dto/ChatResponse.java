package com.wzh.entity.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatResponse {

    /** Agent 回复内容 */
    private String message;

    /** 相关图片（来自知识库） */
    private List<String> relatedImages;

    /** 引用来源 */
    private List<SourceInfo> sources;

    @Data
    public static class SourceInfo {
        private String featureName;
        private String chunkType;
        private float score;
    }
}