package com.chiho.wuagentscope.controller;

import com.chiho.wuagentscope.common.R;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.config.ModelAgentRegistry;
import com.chiho.wuagentscope.config.ModelConfig;
import com.chiho.wuagentscope.model.ChatRequest;
import com.chiho.wuagentscope.service.ChatConversationService;
import com.chiho.wuagentscope.service.ChatService;
import com.chiho.wuagentscope.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * AI 交互控制器
 * <p>
 * 提供与大模型对话交互的接口（SSE 流式 + 同步），支持多模型选择。
 * @author ChiHo
 */
@RestController
@RequestMapping("/ai")
@Profile("!harness")
public class AiController {

    @Resource
    private ChatService chatService;

    @Resource
    private UserService userService;

    @Resource
    private ChatConversationService conversationService;

    @Resource
    private ModelAgentRegistry modelRegistry;

    /**
     * 通用 AI 聊天助手（SSE 流式输出）—— GET 方式（纯文本，向后兼容）
     *
     * @param message 用户消息
     * @param chatId  会话ID（前端生成的唯一标识，相同 ID 恢复历史对话）
     * @param token   用户认证 token
     * @param modelId 模型ID（可选，为空时使用默认模型）
     * @return SSE 流式响应
     */
    @GetMapping("/chat/common/sse")
    public SseEmitter doChatCommonSse(String message, String chatId,
                                      @RequestParam String token,
                                      @RequestParam(required = false) String modelId) {
        Long userId = validateToken(token);
        conversationService.getOrCreateConversation(userId, chatId, message, modelId);

        SseEmitter emitter = new SseEmitter(300_000L);
        chatService.chatStream(userId, chatId, message, modelId)
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
     * 通用 AI 聊天助手（SSE 流式输出）—— POST 方式（支持多模态：文本 + 图片）
     *
     * @param request 聊天请求体（包含文本、图片URL、Base64图片等）
     * @return SSE 流式响应
     */
    @PostMapping("/chat/common/sse")
    public SseEmitter doChatCommonSsePost(@RequestBody ChatRequest request) {
        Long userId = validateToken(request.getToken());
        conversationService.getOrCreateConversation(userId, request.getChatId(),
                request.getMessage(), request.getModelId());

        SseEmitter emitter = new SseEmitter(300_000L);
        chatService.chatStream(userId, request.getChatId(), request.getMessage(),
                        request.getModelId(), request.getImageUrls(), request.getImages())
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
     * 通用 AI 聊天助手（同步调用）—— GET 方式（纯文本，向后兼容）
     */
    @GetMapping("/chat/common")
    public R<String> doChatCommon(String message, String chatId,
                                  @RequestParam String token,
                                  @RequestParam(required = false) String modelId) {
        Long userId = validateToken(token);
        conversationService.getOrCreateConversation(userId, chatId, message, modelId);
        String response = chatService.chat(userId, chatId, message, modelId);
        return R.success(response);
    }

    /**
     * 通用 AI 聊天助手（同步调用）—— POST 方式（支持多模态）
     */
    @PostMapping("/chat/common")
    public R<String> doChatCommonPost(@RequestBody ChatRequest request) {
        Long userId = validateToken(request.getToken());
        conversationService.getOrCreateConversation(userId, request.getChatId(),
                request.getMessage(), request.getModelId());
        String response = chatService.chat(userId, request.getChatId(), request.getMessage(),
                request.getModelId(), request.getImageUrls(), request.getImages());
        return R.success(response);
    }

    /**
     * 获取可用模型列表
     */
    @GetMapping("/models")
    public R<List<ModelConfig>> getAvailableModels() {
        return R.success(modelRegistry.getAvailableModels());
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
