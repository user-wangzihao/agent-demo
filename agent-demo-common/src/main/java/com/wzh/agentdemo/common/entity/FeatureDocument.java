package com.wzh.agentdemo.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("feature_document")
public class FeatureDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 功能名称 */
    private String featureName;

    /** 英文名 */
    private String englishName;

    /** 编制人 */
    private String author;

    /** 功能版本 */
    private String version;

    /** 发行日期 */
    private String publishDate;

    /** 所属公司 */
    private String company;

    /** 功能简介 JSON */
    private String featureIntro;

    /** 功能描述 JSON数组 */
    private String featureDetails;

    /** 操作指南 JSON */
    private String operationGuide;

    /** 常见问题 JSON */
    private String faq;

    /** 关联视频URL列表 JSON */
    private String videoUrls;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** 是否已向量化 0-未向量化 1-已向量化 */
    private Integer vectorized;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
