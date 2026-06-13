package com.chiho.wuagentscope.controller;

import com.chiho.wuagentscope.common.R;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.model.ConversationVO;
import com.chiho.wuagentscope.model.MessageVO;
import com.chiho.wuagentscope.service.ChatConversationService;
import com.chiho.wuagentscope.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 * <p>
 * 职责：接收请求、校验 token、调用 Service、返回统一格式。
 * @author ChiHo
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatConversationService chatConversationService;

    @Resource
    private UserService userService;

    /**
     * 获取用户的会话列表
     */
    @GetMapping("/conversations")
    public R<List<ConversationVO>> listConversations(@RequestParam String token) {
        Long userId = validateToken(token);
        return R.success(chatConversationService.listConversations(userId));
    }

    /**
     * 更新会话名称
     */
    @PutMapping("/conversation/name")
    public R<String> updateConversationName(@RequestParam String token,
                                            @RequestParam String conversationId,
                                            @RequestParam String newName) {
        Long userId = validateToken(token);
        chatConversationService.updateConversationName(userId, conversationId, newName);
        return R.success("会话名称更新成功");
    }

    /**
     * 查询对话历史消息列表
     */
    @GetMapping("/conversation/messages")
    public R<List<MessageVO>> getConversationMessages(@RequestParam String token,
                                                      @RequestParam String conversationId) {
        Long userId = validateToken(token);
        return R.success(chatConversationService.getConversationMessages(userId, conversationId));
    }

    /**
     * 删除指定会话
     */
    @DeleteMapping("/conversation")
    public R<String> deleteConversation(@RequestParam String token, @RequestParam String conversationId) {
        Long userId = validateToken(token);
        chatConversationService.deleteConversation(userId, conversationId);
        return R.success("会话删除成功");
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
