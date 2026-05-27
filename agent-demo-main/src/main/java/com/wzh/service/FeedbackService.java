package com.wzh.service;

import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import com.wzh.config.SemanticCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 反馈服务 (第六刀 Batch 4-3: 从 AgentService 拆出; 第3刀 B3-c: 接入语义缓存负反馈).
 *
 * <p><b>职责</b>: 处理用户对 AI 回答的反馈 (点赞 / 点踩 + 可选原因), 把评分、原因、时间
 * 写回 chat_message 表对应行.</p>
 *
 * <p><b>设计动机</b>: 反馈是质量改进闭环的入口 (后续可衍生: 负反馈聚合分析、按 feature
 * 看回答满意度、引导用户反馈进 FAQ 候选池等). 留独立服务为未来扩展提供锚点,
 * 避免反馈相关逻辑散落在多个服务里.</p>
 *
 * <p><b>B3-c 接入语义缓存负反馈 (B5 三入口之一)</b>: 用户点踩 (rating=-1) 时, 若该消息绑定
 * 了 cacheKey (无论是命中产生还是新写入), 给该 cacheKey 累加点踩负反馈分. 三入口设计:</p>
 * <ul>
 *   <li>点踩 -2 (本服务, 已实现)</li>
 *   <li>regenerate -1 (Controller, 已实现)</li>
 *   <li>工单提单成功 -3 (B5 待做, 按钮触发, 走伪 user 消息链路)</li>
 * </ul>
 *
 * <p><b>当前实现</b>: 极简. 未来扩展点 (按需添加):
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
    private final SemanticCacheService semanticCacheService;
    private final SemanticCacheProperties semanticCacheProperties;

    /**
     * 提交对单条 AI 消息的反馈.
     *
     * <p><b>点踩联动语义缓存 (B3-c)</b>: 仅当 rating=-1 且消息有 cacheKey 时,
     * 同步给缓存累加点踩负反馈分 (权重见 {@link SemanticCacheProperties#getFeedbackWeightDislike}).
     * cacheKey 来源:</p>
     * <ul>
     *   <li>命中场景 (cache_hit_layer 非空): 点踩等于"用户对缓存返回的答案不满意"</li>
     *   <li>新写入场景 (cache_hit_layer 为空): 点踩等于"用户对刚被存入缓存的新答案不满意,
     *       未来同类查询的缓存命中也是这个不好的答案"</li>
     * </ul>
     * <p>两种场景都应该给 cacheKey 打分, 设计上对称.</p>
     *
     * <p><b>容错</b>: incrementFeedback 内部已 try/catch, 缓存联动失败不影响反馈本身的落库.</p>
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

        // B3-c: 点踩 → 给关联的 cacheKey 累加负反馈分.
        // 严格匹配 rating == -1; 点赞和反馈撤销 (rating=0 之类的) 不触发负反馈.
        if (rating != null && rating == -1) {
            String cacheKey = message.getCacheKey();
            if (cacheKey != null && !cacheKey.isBlank()) {
                int weight = semanticCacheProperties.getFeedbackWeightDislike();
                semanticCacheService.incrementFeedback(cacheKey, weight);
                log.info("[feedback] dislike +{} applied to cacheKey={} messageId={}",
                        weight, cacheKey, messageId);
            } else {
                // cacheKey 为空的常见场景:
                // 1. regenerate 出的新 assistant 消息 (handleDone 因 isRegenerate=true 跳过写缓存);
                // 2. chitchat / admin / 未启用缓存意图等不进缓存的链路;
                // 3. 缓存写入失败的兜底 (Redis 挂等场景, 见 SemanticCacheService 容错策略).
                //
                // 决策记录 (B3-c): 不做 history 回溯找最近 cacheKey, 也不让 regenerate 新消息
                // 继承 oldCacheKey. 理由:
                //   a) regenerate 出的新答案点踩 = 对新答案的不满, 新答案没缓存条目可惩罚;
                //   b) oldCacheKey 已在 regenerate 时收过 +1, 用户的不满信号没丢;
                //   c) 用户若继续不满会再 regenerate / 发新 query, oldCacheKey 持续累加直到
                //      触阈被 DEGRADED — 负反馈系统是收敛的, 不需要 100% 不漏打;
                //   d) 让 cache_key 字段保持语义纯净 (绑定 = 该消息直接关联缓存条目),
                //      不引入"上一代消息的 cacheKey"等隐式回溯.
                //
                // chat_message.feedback_rating / feedback_reason 仍完整入库, 离线分析 (评估
                // CI / 数据飞轮) 时仍可消费这条负反馈, 只是不参与在线 cache 打分.
                log.info("[feedback] dislike on messageId={} skip cache layer: cacheKey is null. " +
                        "Likely regenerate-produced answer (oldCacheKey already got regenerate-weight " +
                        "feedback) or non-cacheable intent. Negative signal preserved in chat_message " +
                        "but not propagated to cache.",
                        messageId);
            }
        }
    }
}