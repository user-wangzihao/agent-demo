package com.wzh.agentdemo.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FAQ 候选池实体(来自 TicketSystem 工单提交)。
 *
 * <p>设计原则:内容字段(question / answer / images / feature)与 {@code FaqDocument}
 * 字段命名和类型完全一致,审核通过时可以直接复制到 faq_document 表。</p>
 *
 * <p>生命周期:
 * <ul>
 *   <li>PENDING  - 待审核(由 TicketSystem webhook 提交后初始态)</li>
 *   <li>LEARNED  - 已学习(管理员审核通过,已写入 faq_document + faq_vectors)</li>
 *   <li>REJECTED - 已拒绝(管理员审核拒绝,reviewer_note 必填理由)</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-14 (第五刀)
 */
@Data
@TableName("faq_candidate")
public class FaqCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    // ============ 来源信息 ============

    /** 来源工单ID(TicketSystem ticket.id) */
    private Long sourceTicketId;

    /** 来源工单编号 */
    private String sourceTicketNo;

    /** 提交技术员ID */
    private Long submittedById;

    /** 提交技术员姓名 */
    private String submittedByName;

    // ============ FAQ 内容(字段对齐 faq_document) ============

    /** 问题 */
    private String question;

    /** 问题图片 JSON 数组字符串 */
    private String questionImages;

    /** 答案 */
    private String answer;

    /** 答案图片 JSON 数组字符串 */
    private String answerImages;

    /** 关联功能ID(可空) */
    private Long relatedFeatureId;

    /** 关联功能名(默认"通用FAQ") */
    private String relatedFeatureName;

    // ============ 审核控制 ============

    /** 审核状态:PENDING / LEARNED / REJECTED */
    private String reviewStatus;

    /** 审核管理员ID */
    private Long reviewerId;

    /** 审核管理员姓名 */
    private String reviewerName;

    /** 审核备注(拒绝时填理由) */
    private String reviewerNote;

    /** 审核时间 */
    private LocalDateTime reviewedTime;

    /** 学习后生成的 faq_document.id */
    private Long promotedFaqId;

    // ============ 标准字段 ============

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}