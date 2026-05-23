package com.wzh.entity.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 大屏滚动时间线的单条记录 (B4 引入).
 *
 * <p><b>定位</b>: 大屏右侧"最近 N 条对话"流式列表. 前端按时间倒序展示, 每条卡片
 * 显示时间 / 用户 / 意图 / 耗时, 给面试官"系统正活着、正在处理用户请求"的视觉冲击.</p>
 *
 * <p><b>数据源</b>: chat_session JOIN chat_message JOIN sys_user 三表关联.
 * 一条 TimelineItem 对应一轮对话 (user 消息 + 对应的 assistant 消息).</p>
 *
 * <p><b>为什么不直接返回 ChatMessage</b>: ChatMessage 实体字段太多 (content 可能上千字),
 * 滚动列表只需要摘要. 用专门 DTO 避免泄露 / 减少传输体积.</p>
 *
 * @author wzh
 * @since 2026-05-22 (B4)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineItem {

    /** chat_message 表的 id (user 消息那行的 id). 前端可作 React key. */
    private Long messageId;

    /** chat_session.id. 前端可点击跳转到对话详情. */
    private Long sessionId;

    /** 发起此轮对话的用户昵称 (来自 sys_user.nickname, 不存在则降级为 username). */
    private String userNickname;

    /** 用户角色 (admin / user), 大屏可用不同颜色区分管理员请求和普通用户请求. */
    private String userRole;

    /**
     * 用户提问的简要摘要 (前 30 字截断), 完整 content 不下发避免敏感泄露.
     */
    private String questionSummary;

    /**
     * 本轮对话的意图分类 (chat_message.intent 字段 — 如果将来加).
     * 当前从 feature_name 反推 + 兜底 "default". 等 schema 加 intent 字段后切回真实值.
     */
    private String intentCode;

    /**
     * 命中的 feature 名 (chat_message.feature_name). 可能为 null (闲聊 / admin 命令).
     */
    private String matchedFeature;

    /** 是否触发 FAQ 命中 (B4 新字段 chat_message.faq_hit). */
    private boolean faqHit;

    /** 本轮对话总耗时 (毫秒). 当前从 user 和 assistant 消息的时间差近似. */
    private long totalLatencyMs;

    /** 用户消息的创建时间. */
    private LocalDateTime createTime;
}
