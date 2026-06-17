package com.chiho.wuagentscope.service;

import com.chiho.wuagentscope.entity.AgentCallLogDO;
import com.chiho.wuagentscope.mapper.AgentCallLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.*;
import io.agentscope.core.model.ChatUsage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可观测性事件消费器
 * <p>
 * 消费 ReActAgent.streamEvents() 的事件流，将关键指标持久化到 MySQL。
 * 使用 doOnNext 作为副作用，不阻塞或改变原始事件流。
 * <p>
 * 记录的关键事件：
 * - MODEL_CALL_END: LLM 调用完成（token 用量、耗时）
 * - TOOL_RESULT_END: 工具调用完成（工具名、结果状态）
 * - AGENT_END: 在流结束时自动汇总（总耗时、迭代次数、总 token）
 * <p>
 * 关联方式：所有事件在同一个 Flux 中顺序到达，使用局部累加器 + doOnComplete 汇总，
 * 避免 ThreadLocal 在 Reactor 线程模型下的风险。
 *
 * @author ChiHo
 */
@Component
@Slf4j
public class ObservabilityEventSink {

    @Resource
    private AgentCallLogMapper agentCallLogMapper;

    @Value("${agentscope.ollama.model-name:unknown}")
    private String modelName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 包装事件流，注入可观测性副作用
     * <p>
     * 流程：
     * 1. doOnNext: 逐个处理事件，MODEL_CALL_END / TOOL_RESULT_END 立即写 DB
     * 2. doOnComplete: 流结束时汇总写入 AGENT_END 记录
     * 3. doOnError: 记录异常日志
     *
     * @param events         原始事件流
     * @param userId         用户ID
     * @param conversationId 会话ID
     * @return 包装后的事件流（与原始流行为一致）
     */
    public Flux<AgentEvent> wrapStream(Flux<AgentEvent> events, String userId, String conversationId) {
        // 用局部变量累加，天然安全：同一个 Flux 的 doOnNext/doOnComplete 顺序执行
        String runId = UUID.randomUUID().toString().replace("-", "");
        AtomicLong startTime = new AtomicLong(0);
        AtomicInteger iterations = new AtomicInteger(0);
        AtomicLong totalInputTokens = new AtomicLong(0);
        AtomicLong totalOutputTokens = new AtomicLong(0);

        return events
                .doOnNext(event -> {
                    try {
                        handleEvent(event, runId, userId, conversationId,
                                startTime, iterations, totalInputTokens, totalOutputTokens);
                    } catch (Exception e) {
                        log.warn("ObservabilityEventSink 处理事件异常: type={}", event.getType(), e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        saveAgentEnd(runId, userId, conversationId,
                                startTime.get(), iterations.get(),
                                totalInputTokens.get(), totalOutputTokens.get());
                    } catch (Exception e) {
                        log.warn("ObservabilityEventSink 保存 AGENT_END 异常", e);
                    }
                })
                .doOnError(e -> log.error("事件流异常: userId={}, runId={}", userId, runId, e));
    }

    /**
     * 处理单个事件
     */
    private void handleEvent(AgentEvent event, String runId, String userId, String conversationId,
                             AtomicLong startTime, AtomicInteger iterations,
                             AtomicLong totalInputTokens, AtomicLong totalOutputTokens) {
        if (event instanceof AgentStartEvent) {
            startTime.set(System.currentTimeMillis());
        } else if (event instanceof ModelCallEndEvent e) {
            handleModelCallEnd(e, runId, userId, conversationId, iterations, totalInputTokens, totalOutputTokens);
        } else if (event instanceof ToolResultEndEvent e) {
            handleToolResultEnd(e, runId, userId, conversationId);
        }
        // AGENT_END 不在这里处理，由 doOnComplete 汇总写入
    }

    /**
     * LLM 调用结束：立即写入单次调用记录 + 累加总量
     */
    private void handleModelCallEnd(ModelCallEndEvent event, String runId, String userId, String conversationId,
                                    AtomicInteger iterations, AtomicLong totalInputTokens, AtomicLong totalOutputTokens) {
        iterations.incrementAndGet();

        ChatUsage usage = event.getUsage();

        // 累加 token 到总量
        if (usage != null) {
            totalInputTokens.addAndGet(usage.getInputTokens());
            totalOutputTokens.addAndGet(usage.getOutputTokens());
        }

        // 写入单次调用记录
        AgentCallLogDO logDO = buildBaseLog(runId, userId, conversationId);
        logDO.setEventType("MODEL_CALL_END");
        logDO.setModelName(modelName);

        if (usage != null) {
            logDO.setInputTokens(usage.getInputTokens());
            logDO.setOutputTokens(usage.getOutputTokens());
            if (usage.getTime() > 0) {
                logDO.setDurationMs((long) (usage.getTime() * 1000));
            }
        }

        logDO.setDetail(toJson(event));
        saveLog(logDO);
    }

    /**
     * 工具调用结束：立即写入记录
     */
    private void handleToolResultEnd(ToolResultEndEvent event, String runId, String userId, String conversationId) {
        AgentCallLogDO logDO = buildBaseLog(runId, userId, conversationId);
        logDO.setEventType("TOOL_RESULT_END");
        logDO.setToolName(event.getToolCallName());
        logDO.setToolState(event.getState() != null ? event.getState().name() : null);
        logDO.setDetail(toJson(event));
        saveLog(logDO);
    }

    /**
     * 流结束时汇总写入 AGENT_END 记录
     */
    private void saveAgentEnd(String runId, String userId, String conversationId,
                              long startTime, int iterations, long inputTokens, long outputTokens) {
        long durationMs = startTime > 0 ? System.currentTimeMillis() - startTime : 0;

        log.info("Agent 运行结束: runId={}, userId={}, iterations={}, durationMs={}, inputTokens={}, outputTokens={}",
                runId, userId, iterations, durationMs, inputTokens, outputTokens);

        AgentCallLogDO logDO = buildBaseLog(runId, userId, conversationId);
        logDO.setEventType("AGENT_END");
        logDO.setDurationMs(durationMs);
        logDO.setModelName(modelName);
        logDO.setInputTokens((int) inputTokens);
        logDO.setOutputTokens((int) outputTokens);
        saveLog(logDO);
    }

    /**
     * 构建基础日志对象
     */
    private AgentCallLogDO buildBaseLog(String runId, String userId, String conversationId) {
        AgentCallLogDO logDO = new AgentCallLogDO();
        logDO.setRunId(runId);
        logDO.setUserId(userId);
        logDO.setConversationId(conversationId);
        logDO.setCreatedAt(LocalDateTime.now());
        return logDO;
    }

    /**
     * 异步保存日志（避免阻塞事件流）
     */
    @Async
    public void saveLog(AgentCallLogDO logDO) {
        try {
            agentCallLogMapper.insert(logDO);
        } catch (Exception e) {
            log.warn("保存 Agent 调用日志失败: eventType={}, runId={}", logDO.getEventType(), logDO.getRunId(), e);
        }
    }

    /**
     * 事件序列化为 JSON（用于 detail 字段）
     */
    private String toJson(AgentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.debug("事件序列化失败: {}", event.getType(), e);
            return null;
        }
    }
}
