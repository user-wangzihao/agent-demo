package com.wzh.mapper;

import com.wzh.entity.dto.dashboard.TimelineItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 大屏聚合查询 Mapper (B4 引入).
 *
 * <p><b>定位</b>: 大屏 KPI 卡 #1 / #3 / #5 + 滚动时间线 — 这些都是
 * "业务实体的当前状态/计数", 不适合走 Prometheus 时序聚合 (实体状态语义),
 * 直接走 SQL 单次聚合即可.</p>
 *
 * <p><b>不继承 BaseMapper</b>: 本 mapper 不绑定某个特定实体, 而是跨多个业务表
 * 做聚合 (chat_message / chat_session / faq_candidate / ticket_*). MyBatis-Plus
 * BaseMapper 只适合单实体 CRUD, 跨表聚合用纯 MyBatis 注解更直接.</p>
 *
 * <p><b>SQL 性能</b>: 所有 COUNT 都按 create_time 范围筛选, 假设表已经在
 * create_time 字段建索引 (MyBatis-Plus @TableField 通常自动建). 单次大屏刷新
 * 这里跑 5-6 条 SQL, 总耗时应在 50ms 以内.</p>
 *
 * <p><b>时间字段约定</b>: 接 LocalDate 入参 ("今日"), Service 层算好 [当天00:00, 次日00:00)
 * 半开区间. 不接 LocalDateTime 入参防 timezone 飘.</p>
 *
 * @author wzh
 * @since 2026-05-22 (B4)
 */
@Mapper
public interface DashboardMapper {

    // ==================== 卡 1: 今日对话 ====================

    /**
     * 某一天 (00:00 ~ 次日 00:00) 的 user 消息数.
     * 用 role='user' 滤掉 assistant 消息, 1 条 user 消息 = 1 轮对话.
     */
    @Select("""
            SELECT COUNT(*) FROM chat_message
            WHERE role = 'user'
              AND create_time >= #{dayStart}
              AND create_time < #{dayEnd}
            """)
    long countUserMessagesInRange(@Param("dayStart") LocalDate dayStart,
                                   @Param("dayEnd") LocalDate dayEnd);

    /**
     * 某一天活跃过的用户数 (有任意 user 消息).
     * JOIN chat_session 拿 user_id, COUNT(DISTINCT user_id) 去重.
     */
    @Select("""
            SELECT COUNT(DISTINCT s.user_id)
            FROM chat_session s
            JOIN chat_message m ON m.session_id = s.id
            WHERE m.role = 'user'
              AND m.create_time >= #{dayStart}
              AND m.create_time < #{dayEnd}
            """)
    long countActiveUsersInRange(@Param("dayStart") LocalDate dayStart,
                                  @Param("dayEnd") LocalDate dayEnd);

    // ==================== 卡 3: FAQ 命中率 ====================

    /**
     * 某一天 assistant 消息中 faq_hit = TRUE 的条数.
     *
     * <p><b>为什么是 role='assistant'</b>: faq_hit 字段在 MainGraphSseController 落库 assistant
     * 消息时写入 (来源是 merger 节点处理后的 sources 列表是否含 FAQ 来源). 写到 assistant 行而非
     * user 行的考量: assistant 是回答主体, "回答里用了 FAQ" 是更严谨的语义; 不需要额外 update
     * user 行节省一次写盘.</p>
     *
     * <p><b>分母怎么算</b>: 通常用 user 消息数当分母 (1 user = 1 轮对话), 由 Service 层负责
     * 取除法 — 一轮对话有 1 user + 1 assistant, faq_hit assistant 数 / user 数 = 命中率.</p>
     */
    @Select("""
            SELECT COUNT(*) FROM chat_message
            WHERE role = 'assistant'
              AND faq_hit = TRUE
              AND create_time >= #{dayStart}
              AND create_time < #{dayEnd}
            """)
    long countFaqHitInRange(@Param("dayStart") LocalDate dayStart,
                             @Param("dayEnd") LocalDate dayEnd);

    // ==================== 卡 5: 数据飞轮 + 工单 ====================

    /**
     * faq_candidate 表当前 status='pending' 的总条数 (跨时间).
     * 这是"当前待办量", 不限时间范围.
     */
    @Select("SELECT COUNT(*) FROM faq_candidate WHERE status = 'pending'")
    long countFaqCandidatePending();

    /**
     * 某一天新建的 faq_candidate 条数 (按 create_time).
     * 用于算"较昨日 +N"的 delta 指标.
     */
    @Select("""
            SELECT COUNT(*) FROM faq_candidate
            WHERE create_time >= #{dayStart}
              AND create_time < #{dayEnd}
            """)
    long countFaqCandidateInRange(@Param("dayStart") LocalDate dayStart,
                                   @Param("dayEnd") LocalDate dayEnd);

    /**
     * 某一天 ticket_system 创建的工单数. 工单表在 TicketSystem 子项目里, 通过
     * AgentDemo 数据库的 ticket_local_cache 或反查 TicketSystem 接口拿都行 —
     * 当前实现假设 AgentDemo 直接 join 同库的 ticket 影子表. 若没有, Service 层
     * 走 HTTP 调 TicketSystem 拿数也可, 这里先留 SQL 接口.
     *
     * <p><b>实际表名</b>: 项目里 TicketSystem 的工单存在 ticket_system 库的 t_ticket 表,
     * 我们假设 AgentDemo 配的数据源也能访问这张表 (通过 DB user 跨库授权或视图),
     * 或者数据已被同步到 AgentDemo 主库的 t_ticket. 若都不通, B4 这里返回 0
     * (Service 层兜底), 不阻塞主路径.</p>
     *
     * <p>SQL 中表名暂用 {@code t_ticket}, 你确认实际表名后改这一处即可.</p>
     */
    @Select("""
            SELECT COUNT(*) FROM t_ticket
            WHERE create_time >= #{dayStart}
              AND create_time < #{dayEnd}
            """)
    long countTicketsInRange(@Param("dayStart") LocalDate dayStart,
                              @Param("dayEnd") LocalDate dayEnd);

    // ==================== 滚动时间线 ====================

    /**
     * 最近 N 条 user 消息的时间线条目, 按 create_time 倒序.
     *
     * <p>三表关联: chat_message (主) + chat_session + sys_user.
     * 取 user 消息这一侧的 id 和 content, faq_hit, feature_name; 关联 session
     * 拿 user_id, 再关联 sys_user 拿 nickname / role.</p>
     *
     * <p><b>耗时计算</b>: 通过当前 user 消息和**同 session 紧邻其后的 assistant 消息**
     * 的时间差近似. 用窗口函数会更精确但 MySQL 8 才支持, 这里用相关子查询稳妥.</p>
     *
     * <p><b>问题摘要</b>: content 截断到 60 字, 防大屏行高失控.</p>
     */
    @Select("""
            SELECT
                m.id AS messageId,
                m.session_id AS sessionId,
                COALESCE(u.nickname, u.username, '匿名') AS userNickname,
                COALESCE(u.role, 'user') AS userRole,
                SUBSTRING(m.content, 1, 60) AS questionSummary,
                'default' AS intentCode,
                m.feature_name AS matchedFeature,
                COALESCE(m.faq_hit, FALSE) AS faqHit,
                COALESCE(
                    TIMESTAMPDIFF(MICROSECOND, m.create_time,
                        (SELECT m2.create_time FROM chat_message m2
                         WHERE m2.session_id = m.session_id
                           AND m2.role = 'assistant'
                           AND m2.create_time > m.create_time
                         ORDER BY m2.create_time ASC LIMIT 1)
                    ) / 1000, 0
                ) AS totalLatencyMs,
                m.create_time AS createTime
            FROM chat_message m
            LEFT JOIN chat_session s ON s.id = m.session_id
            LEFT JOIN sys_user u ON u.id = s.user_id
            WHERE m.role = 'user'
            ORDER BY m.create_time DESC
            LIMIT #{limit}
            """)
    List<TimelineItem> selectRecentTimeline(@Param("limit") int limit);
}
