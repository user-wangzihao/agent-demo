package com.wzh.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.agentdemo.common.entity.ChatMessage;
import com.wzh.agentdemo.common.entity.ChatSession;
import com.wzh.agentdemo.common.mapper.ChatMessageMapper;
import com.wzh.agentdemo.common.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话导出服务 (第六刀 Batch 4-2: 从 AgentService 拆出).
 *
 * <p><b>职责</b>: 把一段对话 (chat_session + chat_message) 序列化成 Markdown 文本,
 * 供前端"导出会话"按钮下载. 包含会话标题、各轮 user / assistant 消息、用户上传的截图.</p>
 *
 * <p><b>设计动机</b>: 这是一个纯数据库查询 + 字符串拼接的功能, 既不涉及 LLM 调用,
 * 也不涉及 RAG 或工具编排, 跟"对话生成"完全无关. 之前住在 AgentService 里只是
 * 历史包袱. Batch 4-2 把它独立成单一职责服务.</p>
 *
 * <p><b>不在本服务职责内</b>:
 * <ul>
 *   <li>导出格式扩展 (PDF / HTML / Word) — 当前仅 Markdown, 后续需要时再加方法</li>
 *   <li>HTTP 响应头与文件名设置 — 由调用方 Controller 负责</li>
 *   <li>权限校验 — 由 AuthInterceptor 拦截层负责</li>
 * </ul></p>
 *
 * @author wzh
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionExportService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 把指定会话导出为 Markdown 文本.
     *
     * <p><b>格式约定</b>:
     * <ul>
     *   <li>一级标题: 会话标题</li>
     *   <li>引用块: 导出时间</li>
     *   <li>每轮消息: 三级标题 (👤 用户 / 🤖 AI 助手) + 正文 + 用户截图 (如有)</li>
     *   <li>分隔符: 每轮之间用 {@code ---} 分隔</li>
     * </ul></p>
     *
     * @param sessionId 会话 id
     * @return Markdown 文本
     * @throws RuntimeException 会话不存在时抛出
     */
    public String exportSessionAsMarkdown(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) throw new RuntimeException("会话不存在");

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreateTime));

        StringBuilder md = new StringBuilder();
        md.append("# ").append(session.getTitle()).append("\n\n");
        md.append("> 导出时间: ").append(LocalDateTime.now()).append("\n\n---\n\n");

        for (ChatMessage msg : messages) {
            md.append("user".equals(msg.getRole()) ? "### 👤 用户\n\n" : "### 🤖 AI 助手\n\n");
            md.append(msg.getContent()).append("\n\n");
            if (StrUtil.isNotBlank(msg.getUserImages())) {
                try {
                    List<String> imgs = objectMapper.readValue(msg.getUserImages(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    if (!imgs.isEmpty()) {
                        md.append("**用户上传的截图：**\n\n");
                        for (String img : imgs) md.append("![截图](").append(img).append(")\n\n");
                    }
                } catch (Exception ignored) {
                    // 兼容历史脏数据: 非法 JSON 时静默跳过截图块, 不影响正文导出
                }
            }
            md.append("---\n\n");
        }
        return md.toString();
    }
}