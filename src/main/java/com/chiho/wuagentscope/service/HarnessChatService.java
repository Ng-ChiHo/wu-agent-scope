package com.chiho.wuagentscope.service;

import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * HarnessAgent 实验性聊天服务
 * <p>
 * 与 ChatService 的区别：
 * 1. 使用 HarnessAgent 替代 ReActAgent
 * 2. 支持 Plan 模式控制（enterPlanMode / exitPlanMode）
 * 3. 支持子 Agent 编排（主 Agent 自动调用专家子 Agent）
 * 4. 支持智能记忆管理（自动压缩、摘要）
 * <p>
 * API 兼容性：
 * - call() 和 streamEvents() 接口与 ReActAgent 完全一致
 * - 原有 ChatService 的代码可以零修改直接切换
 * <p>
 * 激活方式：设置 spring.profiles.active: local,harness
 *
 * @author ChiHo
 * @see ChatService 原始 ReActAgent 服务
 */
@Service
@Profile("harness")
@Slf4j
public class HarnessChatService {

    @Resource
    private HarnessAgent harnessAgent;

    @Resource
    private ObservabilityEventSink observabilityEventSink;

    /**
     * 流式聊天（SSE）
     * <p>
     * 与 ChatService.chatStream() 接口完全一致，
     * HarnessAgent 内部自动处理子 Agent 调用、记忆压缩等。
     */
    public Flux<String> chatStream(Long userId, String sessionId, String message) {
        RuntimeContext ctx = buildContext(userId, sessionId);

        // streamEvents() 接口与 ReActAgent 完全一致
        Flux<AgentEvent> rawEvents = harnessAgent.streamEvents(List.of(new UserMessage(message)), ctx);

        Flux<AgentEvent> observedEvents = observabilityEventSink.wrapStream(
                rawEvents, String.valueOf(userId), sessionId);

        return observedEvents
                .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}", userId, sessionId, e));
    }

    /**
     * 流式聊天（SSE），返回原始 AgentEvent 事件流
     */
    public Flux<AgentEvent> chatStreamWithEvents(Long userId, String sessionId, String message) {
        RuntimeContext ctx = buildContext(userId, sessionId);

        Flux<AgentEvent> rawEvents = harnessAgent.streamEvents(List.of(new UserMessage(message)), ctx);

        return observabilityEventSink.wrapStream(rawEvents, String.valueOf(userId), sessionId)
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}", userId, sessionId, e));
    }

    /**
     * 同步聊天（非流式）
     */
    public String chat(Long userId, String sessionId, String message) {
        RuntimeContext ctx = buildContext(userId, sessionId);

        try {
            return harnessAgent.call(List.of(new UserMessage(message)), ctx)
                    .block()
                    .getTextContent();
        } catch (Exception e) {
            log.error("同步聊天异常: userId={}, sessionId={}", userId, sessionId, e);
            throw new BusinessException(ErrorCode.AGENT_RUN_FAILED, "AI助手响应失败: " + e.getMessage());
        }
    }

    // ==================== HarnessAgent 特有功能 ====================

    /**
     * 进入 Plan 模式
     * <p>
     * Plan 模式下，Agent 只能使用只读工具（如 web_search、web_read），
     * 不能执行写操作。适合复杂任务的规划阶段。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    public void enterPlanMode(Long userId, String sessionId) {
        harnessAgent.enterPlanMode(String.valueOf(userId), sessionId);
        log.info("进入 Plan 模式: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * 退出 Plan 模式
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    public void exitPlanMode(Long userId, String sessionId) {
        harnessAgent.exitPlanMode(String.valueOf(userId), sessionId);
        log.info("退出 Plan 模式: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * 查询当前是否处于 Plan 模式
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 是否处于 Plan 模式
     */
    public boolean isPlanModeActive(Long userId, String sessionId) {
        return harnessAgent.isPlanModeActive(String.valueOf(userId), sessionId);
    }

    /**
     * 获取底层 ReActAgent 实例（用于调试或高级场景）
     */
    public HarnessAgent getHarnessAgent() {
        return harnessAgent;
    }

    // ==================== 内部方法 ====================

    private RuntimeContext buildContext(Long userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId)
                .build();
    }
}
