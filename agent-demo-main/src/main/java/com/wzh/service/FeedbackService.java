package com.wzh.service;

import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 反馈服务 (第六刀 Batch 4-3: 从 AgentService 拆出).
 *
 * <p><b>职责</b>: 处理用户对 AI 回答的反馈 (点赞 / 点踩 + 可选原因), 把评分、原因、时间
 * 写回 chat_message 表对应行.</p>
 *
 * <p><b>设计动机</b>: 反馈是质量改进闭环的入口 (后续可衍生: 负反馈聚合分析、按 feature
 * 看回答满意度、引导用户反馈进 FAQ 候选池等). 留独立服务为未来扩展提供锚点,
 * 避免反馈相关逻辑散落在多个服务里.</p>
 *
 * <p><b>当前实现</b>: 极简, 仅 1 个写入方法. 未来扩展点 (按需添加):
 * <ul>
 *   <li>按 feature / time-range 聚合反馈统计</li>
 *   <li>负反馈消息列表 (供管理员复盘)</li>
 *   <li>反馈 → FAQ 候选的自动转换 hook</li>
 * </ul></p>
 *
 * @author wzh
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final ChatMessageMapper chatMessageMapper;

    /**
     * 提交对单条 AI 消息的反馈.
     *
     * @param messageId 消息 id (chat_message.id)
     * @param rating    评分 (1=赞, -1=踩, 业务约定)
     * @param reason    可选原因文本
     * @throws RuntimeException 消息不存在时抛出
     */
    public void submitFeedback(Long messageId, Integer rating, String reason) {
        ChatMessage message = chatMessageMapper.selectById(messageId);
        if (message == null) throw new RuntimeException("消息不存在");
        message.setFeedbackRating(rating);
        message.setFeedbackReason(reason);
        message.setFeedbackTime(LocalDateTime.now());
        chatMessageMapper.updateById(message);
    }
}