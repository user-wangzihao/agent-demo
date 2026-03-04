package com.wzh.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("faq_document")
public class FaqDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String question;

    private String questionImages; // JSON存储

    private String answer;

    private String answerImages; // JSON存储

    private Long relatedFeatureId;

    private String relatedFeatureName;

    private Integer vectorized;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}