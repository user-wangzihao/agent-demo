package com.wzh.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wzh.common.Result;
import com.wzh.common.UserContext;
import com.wzh.entity.ChatMessage;
import com.wzh.entity.ChatSession;
import com.wzh.mapper.ChatMessageMapper;
import com.wzh.mapper.ChatSessionMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "会话管理")
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Operation(summary = "获取当前用户的所有会话")
    @GetMapping("/list")
    public Result<List<ChatSession>> list() {
        Long userId = UserContext.getUserId();
        List<ChatSession> sessions = chatSessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdateTime));
        return Result.success(sessions);
    }

    @Operation(summary = "创建新会话")
    @PostMapping
    public Result<ChatSession> create() {
        ChatSession session = new ChatSession();
        session.setUserId(UserContext.getUserId());
        session.setTitle("新对话");
        chatSessionMapper.insert(session);
        return Result.success(session);
    }

    @Operation(summary = "获取会话的消息历史")
    @GetMapping("/{sessionId}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long sessionId) {
        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreateTime));
        return Result.success(messages);
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable Long sessionId) {
        chatSessionMapper.deleteById(sessionId);
        return Result.success();
    }

    @Operation(summary = "更新会话标题")
    @PutMapping("/{sessionId}/title")
    public Result<Void> updateTitle(@PathVariable Long sessionId, @RequestBody ChatSession req) {
        ChatSession update = new ChatSession();
        update.setId(sessionId);
        update.setTitle(req.getTitle());
        chatSessionMapper.updateById(update);
        return Result.success();
    }
}
