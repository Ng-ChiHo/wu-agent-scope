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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可观测性事件消费器
 * <p>
 * 消费 ReActAgent.streamEvents() 的事件流，将关键指标持久化到 MySQL。
 * 使用 doOnNext 作为副作用，不阻塞或改变原始事件流。
 * <p>
 * 记录的关键事件：
 * - MODEL_CALL_END: LLM 调用完成（token 用量、耗时）
 * - TOOL_RESULT_END: 工具调用完成（工具名、结果状态）
 * - AGENT_START / AGENT_END: Agent 生命周期（总耗时、迭代次数）
 * <p>
 * 与 OtelTracingMiddleware 互不干扰：
 * - OtelTracingMiddleware 工作在 Middleware 链（框架内部 call 链）
 * - 本类工作在 streamEvents() 的 Flux（对外事件流）
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

    /** 记录每个 run 的开始时间，用于计算 Agent 总耗时 */
    private final Map<String, Long> runStartTimes = new ConcurrentHashMap<>();

    /** 记录每个 run 的用户信息 */
    private final Map<String, RunMeta> runMetaMap = new ConcurrentHashMap<>();

    /**
     * 包装事件流，注入可观测性副作用
     * <p>
     * 使用 doOnNext 消费关键事件并异步写入 MySQL，
     * 不阻塞原始事件流，SSE 推送给前端的 TEXT_BLOCK_DELTA 不受影响。
     *
     * @param events         原始事件流
     * @param userId         用户ID
     * @param conversationId 会话ID
     * @return 包装后的事件流（与原始流行为一致）
     */
    public Flux<AgentEvent> wrapStream(Flux<AgentEvent> events, String userId, String conversationId) {
        return events.doOnNext(event -> {
            try {
                handleEvent(event, userId, conversationId);
            } catch (Exception e) {
                // 可观测性异常不应影响主流程
                log.warn("ObservabilityEventSink 处理事件异常: type={}", event.getType(), e);
            }
        });
    }

    /**
     * 处理单个事件（使用 instanceof 替代 pattern matching switch，兼容 Java 8）
     */
    private void handleEvent(AgentEvent event, String userId, String conversationId) {
        if (event instanceof AgentStartEvent) {
            handleAgentStart((AgentStartEvent) event, userId, conversationId);
        } else if (event instanceof AgentEndEvent) {
            handleAgentEnd((AgentEndEvent) event, userId, conversationId);
        } else if (event instanceof ModelCallEndEvent) {
            handleModelCallEnd((ModelCallEndEvent) event, userId, conversationId);
        } else if (event instanceof ToolResultEndEvent) {
            handleToolResultEnd((ToolResultEndEvent) event, userId, conversationId);
        }
        // 其他事件不持久化
    }

    /**
     * Agent 开始：记录 run 起始时间和元信息
     */
    private void handleAgentStart(AgentStartEvent event, String userId, String conversationId) {
        String runId = event.getId();
        runStartTimes.put(runId, System.currentTimeMillis());
        runMetaMap.put(runId, new RunMeta(userId, conversationId));
    }

    /**
     * Agent 结束：记录总耗时
     */
    private void handleAgentEnd(AgentEndEvent event, String userId, String conversationId) {
        String runId = event.getId();
        Long startTime = runStartTimes.remove(runId);
        runMetaMap.remove(runId);

        if (startTime == null) {
            return;
        }

        long durationMs = System.currentTimeMillis() - startTime;

        AgentCallLogDO logDO = buildBaseLog(event.getId(), userId, conversationId);
        logDO.setEventType("AGENT_END");
        logDO.setDurationMs(durationMs);

        saveLog(logDO);
    }

    /**
     * LLM 调用结束：记录 token 用量和耗时
     */
    private void handleModelCallEnd(ModelCallEndEvent event, String userId, String conversationId) {
        AgentCallLogDO logDO = buildBaseLog(event.getId(), userId, conversationId);
        logDO.setEventType("MODEL_CALL_END");
        logDO.setModelName(modelName);

        ChatUsage usage = event.getUsage();
        if (usage != null) {
            logDO.setInputTokens(usage.getInputTokens());
            logDO.setOutputTokens(usage.getOutputTokens());
            // getTime() 返回秒，转为毫秒
            if (usage.getTime() > 0) {
                logDO.setDurationMs((long) (usage.getTime() * 1000));
            }
        }

        logDO.setDetail(toJson(event));

        saveLog(logDO);
    }

    /**
     * 工具调用结束：记录工具名和结果状态
     */
    private void handleToolResultEnd(ToolResultEndEvent event, String userId, String conversationId) {
        AgentCallLogDO logDO = buildBaseLog(event.getId(), userId, conversationId);
        logDO.setEventType("TOOL_RESULT_END");
        logDO.setToolName(event.getToolCallName());
        logDO.setToolState(event.getState() != null ? event.getState().name() : null);

        logDO.setDetail(toJson(event));

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

    /**
     * Run 元信息（内部使用）
     */
    private static class RunMeta {
        final String userId;
        final String conversationId;

        RunMeta(String userId, String conversationId) {
            this.userId = userId;
            this.conversationId = conversationId;
        }
    }
}
