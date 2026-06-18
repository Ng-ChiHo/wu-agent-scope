package com.chiho.wuagentscope.service;

import com.chiho.wuagentscope.config.ModelAgentRegistry;
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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 聊天业务服务
 * <p>
 * 封装 AgentScope ReActAgent 的调用，提供：
 * 1. 流式聊天（SSE）—— 通过 streamEvents() 实时返回文本增量
 * 2. 同步聊天 —— 通过 call() 一次性返回完整响应
 * 3. 会话隔离 —— 通过 RuntimeContext(userId, sessionId) 自动管理对话状态
 * 4. 多模型路由 —— 通过 ModelAgentRegistry 按 modelId 选择对应 Agent
 * <p>
 * 核心机制：
 * - ReActAgent 是无状态的单例，所有 per-session 状态由 AgentState 管理
 * - 相同 (userId, sessionId) 的 call() 自动恢复上次对话上下文
 * - 不同 session 完全并行，同 session 自动串行（防止状态竞争）
 * @author ChiHo
 */
@Service
@Profile("!harness")
@Slf4j
public class ChatService {

    @Resource
    private ModelAgentRegistry modelRegistry;

    @Resource
    private ObservabilityEventSink observabilityEventSink;

    /**
     * 流式聊天（SSE）
     * <p>
     * 调用 streamEvents() 获取实时事件流，过滤出文本增量事件（TEXT_BLOCK_DELTA），
     * 将 delta 片段逐个推送给前端。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID（相同 ID 自动恢复历史对话）
     * @param message   用户消息
     * @param modelId   模型ID（可选，为空时使用默认模型）
     * @return 文本增量的 Flux 流，每个元素是一小段文本
     */
    public Flux<String> chatStream(Long userId, String sessionId, String message, String modelId) {
        ReActAgent agent = modelRegistry.getAgent(modelId);
        RuntimeContext ctx = buildContext(userId, sessionId);

        Flux<AgentEvent> rawEvents = agent.streamEvents(List.of(new UserMessage(message)), ctx);
        Flux<AgentEvent> observedEvents = observabilityEventSink.wrapStream(rawEvents, userId, sessionId, modelId);

        return observedEvents
                .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}, modelId={}", userId, sessionId, modelId, e));
    }

    /**
     * 流式聊天（SSE），返回原始 AgentEvent 事件流
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   用户消息
     * @param modelId   模型ID（可选）
     * @return AgentEvent 事件流
     */
    public Flux<AgentEvent> chatStreamWithEvents(Long userId, String sessionId, String message, String modelId) {
        ReActAgent agent = modelRegistry.getAgent(modelId);
        RuntimeContext ctx = buildContext(userId, sessionId);

        Flux<AgentEvent> rawEvents = agent.streamEvents(List.of(new UserMessage(message)), ctx);

        return observabilityEventSink.wrapStream(rawEvents, userId, sessionId, modelId)
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}, modelId={}", userId, sessionId, modelId, e));
    }

    /**
     * 同步聊天（非流式）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   用户消息
     * @param modelId   模型ID（可选）
     * @return Agent 的完整回复文本
     */
    public String chat(Long userId, String sessionId, String message, String modelId) {
        ReActAgent agent = modelRegistry.getAgent(modelId);
        RuntimeContext ctx = buildContext(userId, sessionId);

        try {
            return agent.call(List.of(new UserMessage(message)), ctx)
                    .block()
                    .getTextContent();
        } catch (Exception e) {
            log.error("同步聊天异常: userId={}, sessionId={}, modelId={}", userId, sessionId, modelId, e);
            throw new BusinessException(ErrorCode.AGENT_RUN_FAILED, "AI助手响应失败: " + e.getMessage());
        }
    }

    private RuntimeContext buildContext(Long userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId)
                .build();
    }
}
