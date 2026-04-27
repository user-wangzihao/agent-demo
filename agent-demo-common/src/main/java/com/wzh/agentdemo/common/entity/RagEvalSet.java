package com.wzh.agentdemo.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
 
import java.time.LocalDateTime;
 
/**
 * RAG 评估集
 *
 * <p>每条记录是一个 (query, expected_chunks) 对,用于评估检索系统的命中质量。</p>
 */
@Data
@TableName("rag_eval_set")
public class RagEvalSet {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类: 问题类/使用方式类/功能介绍类 */
    private String category;

    /** 功能模块,如 BOM工具、快速涂色 */
    private String subCategory;

    /** 用户问题 */
    private String query;

    /** 正确 chunk_id 列表,逗号分隔 */
    private String expectedChunks;

    /** 参考答案(备查,不参与自动评估) */
    private String expectedAnswer;

    /** 是否启用 */
    private Integer enabled;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}