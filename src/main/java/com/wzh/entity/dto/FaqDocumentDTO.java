package com.wzh.entity.dto;

import lombok.Data;
import java.util.List;

@Data
public class FaqDocumentDTO {

    private Long id;

    /** 问题描述 */
    private String question;

    /** 问题相关图片 */
    private List<String> questionImages;

    /** 答案内容 */
    private String answer;

    /** 答案相关图片 */
    private List<String> answerImages;

    /** 关联功能文档ID（可为空） */
    private Long relatedFeatureId;

    /** 关联功能名称（前端展示用） */
    private String relatedFeatureName;

    /** 是否已向量化 */
    private Integer vectorized;
}