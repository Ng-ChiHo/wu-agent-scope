package com.chiho.wuagentscope.service;

import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import jakarta.annotation.Resource;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 聊天业务服务
 * <p>
 * 封装 AgentScope ReActAgent 的调用，提供：
 * 1. 流式聊天（SSE）—— 通过 streamEvents() 实时返回文本增量
 * 2. 同步聊天 —— 通过 call() 一次性返回完整响应
 * 3. 会话隔离 —— 通过 RuntimeContext(userId, sessionId) 自动管理对话状态
 * <p>
 * 核心机制：
 * - ReActAgent 是无状态的单例，所有 per-session 状态由 AgentState 管理
 * - 相同 (userId, sessionId) 的 call() 自动恢复上次对话上下文
 * - 不同 session 完全并行，同 session 自动串行（防止状态竞争）
 * @author ChiHo
 */
@Service
@Slf4j
public class ChatService {

    @Resource
    private ReActAgent reActAgent;

    @Resource
    private ObservabilityEventSink observabilityEventSink;

    /**
     * 流式聊天（SSE）
     * <p>
     * 调用 streamEvents() 获取实时事件流，过滤出文本增量事件（TEXT_BLOCK_DELTA），
     * 将 delta 片段逐个推送给前端。
     * <p>
     * 事件类型包括：
     * - TEXT_BLOCK_DELTA: 模型返回的流式文本片段（我们只需要这个）
     * - TOOL_CALL_START: 工具调用开始
     * - TOOL_CALL_END: 工具调用结束
     * - TOOL_RESULT_START/END: 工具执行结果
     * - THINKING_BLOCK_DELTA: 模型思考过程（如果模型支持）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID（相同 ID 自动恢复历史对话）
     * @param message   用户消息
     * @return 文本增量的 Flux 流，每个元素是一小段文本
     */
    public Flux<String> chatStream(Long userId, String sessionId, String message) {
        RuntimeContext ctx = buildContext(userId, sessionId);

        // streamEvents() 获取完整事件流
        Flux<AgentEvent> rawEvents = reActAgent.streamEvents(List.of(new UserMessage(message)), ctx);

        // 先用 observable 桥接消费所有事件（AGENT_END、MODEL_CALL_END 等），再过滤文本给前端
        Flux<AgentEvent> observedEvents = observabilityEventSink.wrapStream(rawEvents, String.valueOf(userId), sessionId);

        return observedEvents
                .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}", userId, sessionId, e));
    }

    /**
     * 流式聊天（SSE），返回原始 AgentEvent 事件流
     * <p>
     * 适用于需要展示工具调用、思考过程等完整信息的场景。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   用户消息
     * @return AgentEvent 事件流
     */
    public Flux<AgentEvent> chatStreamWithEvents(Long userId, String sessionId, String message) {
        RuntimeContext ctx = buildContext(userId, sessionId);

        Flux<AgentEvent> rawEvents = reActAgent.streamEvents(List.of(new UserMessage(message)), ctx);

        // 包装：注入可观测性 sink
        return observabilityEventSink.wrapStream(rawEvents, String.valueOf(userId), sessionId)
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}", userId, sessionId, e));
    }

    /**
     * 同步聊天（非流式）
     * <p>
     * 调用 call() 等待 Agent 完成推理-行动循环后返回最终结果。
     * 适用于不需要实时推送的场景（如 API 调用、后台任务）。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   用户消息
     * @return Agent 的完整回复文本
     */
    public String chat(Long userId, String sessionId, String message) {
        RuntimeContext ctx = buildContext(userId, sessionId);

        try {
            return reActAgent.call(List.of(new UserMessage(message)), ctx)
                    .block()
                    .getTextContent();
        } catch (Exception e) {
            log.error("同步聊天异常: userId={}, sessionId={}", userId, sessionId, e);
            throw new BusinessException(ErrorCode.AGENT_RUN_FAILED, "AI助手响应失败: " + e.getMessage());
        }
    }

    /**
     * 构建 RuntimeContext
     * <p>
     * RuntimeContext 携带 userId 和 sessionId，Agent 在每次 call() 时：
     * 1. 根据 (userId, sessionId) 从 AgentStateStore 加载历史对话状态
     * 2. 将用户消息追加到上下文
     * 3. 执行推理-行动循环
     * 4. call() 结束后自动保存 AgentState
     */
    private RuntimeContext buildContext(Long userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId)
                .build();
    }
}
