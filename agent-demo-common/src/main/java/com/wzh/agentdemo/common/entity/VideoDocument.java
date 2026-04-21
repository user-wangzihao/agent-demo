package com.wzh.agentdemo.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_document")
public class VideoDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联功能文档ID */
    private Long featureId;

    /** 原始文件名 */
    private String originalName;

    /** MinIO存储URL */
    private String fileUrl;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 文件类型 */
    private String fileType;

    /** 视频时长（秒） */
    private Double duration;

    /** 学习状态 0-未学习 1-学习中 2-已学习 3-学习失败 */
    private Integer learnStatus;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}