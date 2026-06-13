package com.chiho.wuagentscope.controller;

import com.chiho.wuagentscope.common.R;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.service.ChatConversationService;
import com.chiho.wuagentscope.service.ChatService;
import com.chiho.wuagentscope.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * AI 交互控制器
 * <p>
 * 提供与大模型对话交互的接口（SSE 流式 + 同步）。
 * @author ChiHo
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private ChatService chatService;

    @Resource
    private UserService userService;

    @Resource
    private ChatConversationService conversationService;

    /**
     * 通用 AI 聊天助手（SSE 流式输出）
     * <p>
     * 推荐使用此接口，实时推送模型生成的文本增量，前端可逐字展示。
     * <p>
     * 调用链路：
     * 1. 验证 token → 获取 userId
     * 2. 自动创建/获取会话记录（MySQL）
     * 3. 创建 SseEmitter（5 分钟超时）
     * 4. 调用 ChatService.chatStream() → ReActAgent.streamEvents()
     * 5. AgentScope 自动从 MysqlAgentStateStore 加载历史对话
     * 6. 模型推理，流式返回 TEXT_BLOCK_DELTA 事件
     * 7. 推理结束后自动保存 AgentState（含完整对话历史）
     *
     * @param message 用户消息
     * @param chatId  会话ID（前端生成的唯一标识，相同 ID 恢复历史对话）
     * @param token   用户认证 token
     * @return SSE 流式响应
     */
    @GetMapping("/chat/common/sse")
    public SseEmitter doChatCommonSse(String message, String chatId, @RequestParam String token) {
        Long userId = validateToken(token);
        conversationService.getOrCreateConversation(userId, chatId, message);

        SseEmitter emitter = new SseEmitter(300_000L);
        chatService.chatStream(userId, chatId, message)
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );
        return emitter;
    }

    /**
     * 通用 AI 聊天助手（同步调用）
     * <p>
     * 等待 Agent 完成推理后一次性返回完整结果。
     *
     * @param message 用户消息
     * @param chatId  会话ID
     * @param token   用户认证 token
     * @return 统一返回格式的 AI 回复
     */
    @GetMapping("/chat/common")
    public R<String> doChatCommon(String message, String chatId, @RequestParam String token) {
        Long userId = validateToken(token);
        conversationService.getOrCreateConversation(userId, chatId, message);
        String response = chatService.chat(userId, chatId, message);
        return R.success(response);
    }

    /**
     * 验证 token 并返回用户 ID
     */
    private Long validateToken(String token) {
        Long userId = userService.getUserIdByToken(token);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_TOKEN);
        }
        return userId;
    }
}
