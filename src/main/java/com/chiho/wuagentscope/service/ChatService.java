package com.chiho.wuagentscope.service;

import com.chiho.wuagentscope.config.ModelAgentRegistry;
import com.chiho.wuagentscope.config.SpecialistAgentRegistry;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.model.ImageData;
import com.chiho.wuagentscope.model.RouteResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import jakarta.annotation.Resource;

import java.util.ArrayList;
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

    @Resource
    private AgentRouterService routerService;

    @Resource
    private SpecialistAgentRegistry specialistAgentRegistry;

    /**
     * 流式聊天（SSE）—— 纯文本
     *
     * @param userId    用户ID
     * @param sessionId 会话ID（相同 ID 自动恢复历史对话）
     * @param message   用户消息
     * @param modelId   模型ID（可选，为空时使用默认模型）
     * @return 文本增量的 Flux 流，每个元素是一小段文本
     */
    public Flux<String> chatStream(Long userId, String sessionId, String message, String modelId) {
        return chatStream(userId, sessionId, message, modelId, null, null);
    }

    /**
     * 流式聊天（SSE）—— 支持多模态（文本 + 图片）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   用户消息（可为空）
     * @param modelId   模型ID
     * @param imageUrls 图片URL列表（可选）
     * @param images    Base64 图片列表（可选）
     * @return 文本增量的 Flux 流
     */
    public Flux<String> chatStream(Long userId, String sessionId, String message,
                                   String modelId, List<String> imageUrls, List<ImageData> images) {
        RouteResult route = routerService.route(message, sessionId);
        log.info("路由结果: userId={}, route={}, confidence={}", userId, route.route(), route.confidence());
        ReActAgent agent = specialistAgentRegistry.getAgent(route.route(), modelId);
        RuntimeContext ctx = buildContext(userId, sessionId);
        UserMessage userMsg = buildUserMessage(message, imageUrls, images);

        Flux<AgentEvent> rawEvents = agent.streamEvents(List.of(userMsg), ctx);
        Flux<AgentEvent> observedEvents = observabilityEventSink.wrapStream(rawEvents, userId, sessionId, modelId);

        return observedEvents
                .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}, modelId={}", userId, sessionId, modelId, e));
    }

    /**
     * 流式聊天（SSE），返回原始 AgentEvent 事件流
     */
    public Flux<AgentEvent> chatStreamWithEvents(Long userId, String sessionId, String message, String modelId) {
        return chatStreamWithEvents(userId, sessionId, message, modelId, null, null);
    }

    /**
     * 流式聊天（SSE），返回原始 AgentEvent 事件流 —— 支持多模态
     */
    public Flux<AgentEvent> chatStreamWithEvents(Long userId, String sessionId, String message,
                                                  String modelId, List<String> imageUrls, List<ImageData> images) {
        RouteResult route = routerService.route(message, sessionId);
        log.info("路由结果: userId={}, route={}, confidence={}", userId, route.route(), route.confidence());
        ReActAgent agent = specialistAgentRegistry.getAgent(route.route(), modelId);
        RuntimeContext ctx = buildContext(userId, sessionId);
        UserMessage userMsg = buildUserMessage(message, imageUrls, images);

        Flux<AgentEvent> rawEvents = agent.streamEvents(List.of(userMsg), ctx);

        return observabilityEventSink.wrapStream(rawEvents, userId, sessionId, modelId)
                .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}, modelId={}", userId, sessionId, modelId, e));
    }

    /**
     * 同步聊天（非流式）—— 纯文本
     */
    public String chat(Long userId, String sessionId, String message, String modelId) {
        return chat(userId, sessionId, message, modelId, null, null);
    }

    /**
     * 同步聊天（非流式）—— 支持多模态
     */
    public String chat(Long userId, String sessionId, String message,
                       String modelId, List<String> imageUrls, List<ImageData> images) {
        RouteResult route = routerService.route(message, sessionId);
        log.info("路由结果: userId={}, route={}, confidence={}", userId, route.route(), route.confidence());
        ReActAgent agent = specialistAgentRegistry.getAgent(route.route(), modelId);
        RuntimeContext ctx = buildContext(userId, sessionId);
        UserMessage userMsg = buildUserMessage(message, imageUrls, images);

        try {
            return agent.call(List.of(userMsg), ctx)
                    .block()
                    .getTextContent();
        } catch (Exception e) {
            log.error("同步聊天异常: userId={}, sessionId={}, modelId={}", userId, sessionId, modelId, e);
            throw new BusinessException(ErrorCode.AGENT_RUN_FAILED, "AI助手响应失败: " + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建多模态 UserMessage
     * <p>
     * 将文本 + 图片URL + Base64图片 统一转换为 AgentScope 的 ContentBlock 列表。
     * AgentScope 的 OllamaChatFormatter 会自动将 ImageBlock 转为 Ollama API 所需的 Base64 images 字段。
     */
    private UserMessage buildUserMessage(String message, List<String> imageUrls, List<ImageData> images) {
        // 纯文本快捷路径
        boolean hasImages = (imageUrls != null && !imageUrls.isEmpty()) || (images != null && !images.isEmpty());
        if (!hasImages) {
            return new UserMessage(message != null ? message : "");
        }

        // 多模态：构建 ContentBlock 列表
        List<ContentBlock> blocks = new ArrayList<>();

        if (message != null && !message.isBlank()) {
            blocks.add(TextBlock.builder().text(message).build());
        }

        if (imageUrls != null) {
            for (String url : imageUrls) {
                if (url != null && !url.isBlank()) {
                    blocks.add(ImageBlock.builder()
                            .source(URLSource.builder().url(url).build())
                            .build());
                }
            }
        }

        if (images != null) {
            for (ImageData img : images) {
                if (img != null && img.getBase64() != null) {
                    blocks.add(ImageBlock.builder()
                            .source(Base64Source.builder()
                                    .mediaType(img.getMimeType() != null ? img.getMimeType() : "image/png")
                                    .data(img.getBase64())
                                    .build())
                            .build());
                }
            }
        }

        return new UserMessage(blocks);
    }

    private RuntimeContext buildContext(Long userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId)
                .build();
    }
}
