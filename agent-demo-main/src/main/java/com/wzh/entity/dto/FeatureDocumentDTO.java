package com.wzh.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class FeatureDocumentDTO {

    private Long id;

    @NotBlank(message = "功能名称不能为空")
    private String featureName;

    private String englishName;

    private String author;

    private String version;

    private String publishDate;

    private String company;

    /** 功能简介 */
    private SectionDTO featureIntro;

    /** 功能描述列表（可动态添加多个） */
    private List<FeatureDetailDTO> featureDetails;

    /** 操作指南 */
    private SectionDTO operationGuide;

    /** 常见问题 */
    private SectionDTO faq;

    /** 关联视频URL列表 */
    private List<String> videoUrls;

    /**
     * 通用段落：描述 + 图片列表
     */
    @Data
    public static class SectionDTO {
        private String description;
        private List<String> images;
    }

    /**
     * 功能描述项：标题 + 描述 + 图片列表
     */
    @Data
    public static class FeatureDetailDTO {
        private String title;
        private String description;
        private List<String> images;
    }
}