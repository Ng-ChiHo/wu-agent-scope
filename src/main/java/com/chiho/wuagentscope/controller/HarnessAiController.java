package com.chiho.wuagentscope.controller;

import com.chiho.wuagentscope.common.R;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.service.ChatConversationService;
import com.chiho.wuagentscope.service.HarnessChatService;
import com.chiho.wuagentscope.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * HarnessAgent 实验性控制器
 * <p>
 * 在原有 AiController 基础上，增加 HarnessAgent 特有功能：
 * - Plan 模式控制（进入/退出/查询）
 * - 子 Agent 编排（自动调用专家子 Agent）
 * - 智能记忆管理（自动压缩、摘要）
 * <p>
 * 激活方式：设置 spring.profiles.active: local,harness
 * <p>
 * 所有接口与原有 AiController 完全兼容，可并行使用。
 *
 * @author ChiHo
 * @see AiController 原始控制器
 */
@RestController
@RequestMapping("/harness/ai")
@Profile("harness")
public class HarnessAiController {

    @Resource
    private HarnessChatService harnessChatService;

    @Resource
    private UserService userService;

    @Resource
    private ChatConversationService conversationService;

    // ==================== 聊天接口（与 AiController 兼容） ====================

    /**
     * HarnessAgent 聊天（SSE 流式输出）
     * <p>
     * 与 AiController.doChatCommonSse() 接口一致，
     * 但内部使用 HarnessAgent，支持子 Agent 编排和智能记忆管理。
     */
    @GetMapping("/chat/sse")
    public SseEmitter doChatSse(String message, String chatId, @RequestParam String token) {
        Long userId = validateToken(token);
        conversationService.getOrCreateConversation(userId, chatId, message);

        SseEmitter emitter = new SseEmitter(300_000L);
        harnessChatService.chatStream(userId, chatId, message)
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
     * HarnessAgent 聊天（同步调用）
     */
    @GetMapping("/chat")
    public R<String> doChat(String message, String chatId, @RequestParam String token) {
        Long userId = validateToken(token);
        conversationService.getOrCreateConversation(userId, chatId, message);
        String response = harnessChatService.chat(userId, chatId, message);
        return R.success(response);
    }

    // ==================== Plan 模式控制 ====================

    /**
     * 进入 Plan 模式
     * <p>
     * Plan 模式下，Agent 只能使用只读工具（如 web_search、web_read），
     * 不能执行写操作。适合复杂任务的规划阶段。
     * <p>
     * 使用场景：
     * - 用户说"帮我规划一下这个任务"
     * - 复杂问题需要先分析再执行
     * - 需要多步骤推理的场景
     *
     * @param chatId 会话ID
     * @param token  用户认证 token
     * @return 操作结果
     */
    @PostMapping("/plan/enter")
    public R<Map<String, Object>> enterPlanMode(
            @RequestParam String chatId,
            @RequestParam String token) {
        Long userId = validateToken(token);
        harnessChatService.enterPlanMode(userId, chatId);
        return R.success(Map.of(
                "planMode", true,
                "message", "已进入 Plan 模式，Agent 将只使用只读工具进行规划"
        ));
    }

    /**
     * 退出 Plan 模式
     *
     * @param chatId 会话ID
     * @param token  用户认证 token
     * @return 操作结果
     */
    @PostMapping("/plan/exit")
    public R<Map<String, Object>> exitPlanMode(
            @RequestParam String chatId,
            @RequestParam String token) {
        Long userId = validateToken(token);
        harnessChatService.exitPlanMode(userId, chatId);
        return R.success(Map.of(
                "planMode", false,
                "message", "已退出 Plan 模式，Agent 恢复完整工具能力"
        ));
    }

    /**
     * 查询当前是否处于 Plan 模式
     *
     * @param chatId 会话ID
     * @param token  用户认证 token
     * @return Plan 模式状态
     */
    @GetMapping("/plan/status")
    public R<Map<String, Object>> getPlanModeStatus(
            @RequestParam String chatId,
            @RequestParam String token) {
        Long userId = validateToken(token);
        boolean isActive = harnessChatService.isPlanModeActive(userId, chatId);
        return R.success(Map.of(
                "planMode", isActive,
                "chatId", chatId
        ));
    }

    // ==================== Agent 信息查询 ====================

    /**
     * 获取 HarnessAgent 配置信息
     * <p>
     * 返回当前 Agent 的配置信息，包括：
     * - 名称、最大迭代次数
     * - 子 Agent 列表
     * - 已启用的功能模块
     */
    @GetMapping("/info")
    public R<Map<String, Object>> getAgentInfo(@RequestParam String token) {
        validateToken(token);

        var agent = harnessChatService.getHarnessAgent();
        return R.success(Map.of(
                "name", agent.getName(),
                "maxIters", agent.getMaxIters(),
                "hasSubagents", agent.getSubagentAgentManager() != null,
                "hasMemory", agent.getCompactionHook() != null,
                "hasPlanMode", true
//                "hasSkillCurator", agent.getSkillCurator() != null
        ));
    }

    // ==================== 内部方法 ====================

    private Long validateToken(String token) {
        Long userId = userService.getUserIdByToken(token);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_TOKEN);
        }
        return userId;
    }
}
