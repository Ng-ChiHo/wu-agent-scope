package com.chiho.wuagentscope.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chiho.wuagentscope.entity.ChatConversationDO;
import com.chiho.wuagentscope.mapper.ChatConversationMapper;
import com.chiho.wuagentscope.model.RouteResult;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.ollama.OllamaOptions;
import io.agentscope.core.model.ollama.ThinkOption;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 意图路由服务
 * <p>
 * 使用轻量级 LLM 模型（qwen3:1.7b）对用户消息进行意图分类，
 * 判断应由哪个模块处理，返回路由结果。
 * <p>
 * 路由上下文持久化策略：
 * - L1: 内存 ConcurrentHashMap（快速，重启丢失）
 * - L2: MySQL ai_chat_conversation.last_route / last_route_msg（持久化）
 * <p>
 * 优先读 L1，miss 时读 L2；写入时双写 L1 + L2。
 *
 * @author ChiHo
 */
@Service
@Profile("!harness")
@Slf4j
public class AgentRouterService {

    private static final String ROUTER_SYSTEM_PROMPT_TEMPLATE = String.join("\n",
            "你是一个意图分类系统。根据用户消息，判断应该由哪个模块处理。",
            "",
            "可选路由：",
            "- general: 通用对话、闲聊、简单问答、翻译、总结、知识问答、编程帮助",
            "- data_analyst: 数据查询、SQL、报表、统计分析、图表可视化、数据库相关问题",
            "- car_advisor: 购车换车咨询、选车推荐、车型对比、预算选车、用车场景（通勤/家用/代步）、换车建议、燃油/混动/纯电选择、国产/合资对比",
            "",
            "%s",
            "只输出 JSON，不要输出其他内容：",
            "{\"route\": \"general\", \"confidence\": 0.95, \"reason\": \"用户在闲聊\"}");

    /** 路由上下文提示模板（有历史时注入） */
    private static final String CONTEXT_HINT = String.join("\n",
            "## 对话上下文（用于判断是否为同一话题的延续）",
            "上一轮路由: %s",
            "上一轮用户消息: %s",
            "如果当前消息是上一轮话题的延续（如追问、补充、细化），优先保持相同路由。",
            "如果当前消息明显切换了话题，按新消息独立判断。",
            "");

    private static final String ROUTER_MODEL_ID = "qwen3:1.7b";

    private static final double CONFIDENCE_THRESHOLD = 0.8;

    private final AgentStateStore agentStateStore;
    private final Toolkit toolkit;
    private final ChatConversationMapper chatConversationMapper;

    private OllamaChatModel routerModel;

    /** L1 缓存：会话级路由上下文，key = sessionId */
    private final ConcurrentHashMap<String, RoutingContext> contextCache = new ConcurrentHashMap<>();

    public AgentRouterService(AgentStateStore agentStateStore, Toolkit toolkit,
                              ChatConversationMapper chatConversationMapper) {
        this.agentStateStore = agentStateStore;
        this.toolkit = toolkit;
        this.chatConversationMapper = chatConversationMapper;
    }

    @PostConstruct
    public void init() {
        routerModel = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName(ROUTER_MODEL_ID)
                .formatter(new OllamaChatFormatter())
                .defaultOptions(OllamaOptions.builder()
                        .thinkOption(ThinkOption.ThinkBoolean.DISABLED)
                        .build())
                .build();

        log.info("路由器初始化完成: model={}, confidenceThreshold={}", ROUTER_MODEL_ID, CONFIDENCE_THRESHOLD);
    }

    /**
     * 对用户消息进行意图分类路由（带会话上下文）
     *
     * @param userMessage 用户输入的消息
     * @param sessionId   会话ID（用于追踪路由上下文，null 则无上下文）
     * @return 路由分类结果，置信度低于阈值时回退为 general
     */
    public RouteResult route(String userMessage, String sessionId) {
        if (userMessage == null || userMessage.isBlank()) {
            return RouteResult.defaultRoute();
        }

        try {
            // 构建路由提示（有上下文时注入历史路由信息）
            String contextHint = "";
            if (sessionId != null) {
                RoutingContext prevCtx = loadContext(sessionId);
                if (prevCtx != null) {
                    contextHint = String.format(CONTEXT_HINT, prevCtx.route, prevCtx.lastMessage);
                }
            }
            String systemPrompt = String.format(ROUTER_SYSTEM_PROMPT_TEMPLATE, contextHint);

            // 直接调用模型，无状态路由
            List<Msg> messages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userMessage)
            );
            ChatResponse chatResponse = routerModel.chat(messages, (OllamaOptions) null);
            String response = chatResponse.getContent().stream()
                    .filter(block -> block instanceof TextBlock)
                    .map(block -> ((TextBlock) block).getText())
                    .reduce("", (a, b) -> a + b);

            RouteResult result = parseRouteResponse(response);

            if (result.confidence() < CONFIDENCE_THRESHOLD) {
                log.debug("路由置信度不足: confidence={}, reason={}, 回退到 general",
                        result.confidence(), result.reason());
                result = new RouteResult("general", result.confidence(), result.reason());
            }

            // 双写路由上下文（L1 内存 + L2 MySQL）
            if (sessionId != null) {
                saveContext(sessionId, result.route(), userMessage);
            }

            log.debug("路由分类结果: route={}, confidence={}, reason={}, sessionId={}",
                    result.route(), result.confidence(), result.reason(), sessionId);
            return result;

        } catch (Exception e) {
            log.error("路由分类异常: message={}, error={}", userMessage, e.getMessage(), e);
            return RouteResult.defaultRoute();
        }
    }

    /**
     * 兼容旧调用（无 sessionId）
     */
    public RouteResult route(String userMessage) {
        return route(userMessage, null);
    }

    /**
     * 清除指定会话的路由上下文（会话删除时调用）
     */
    public void clearContext(String sessionId) {
        if (sessionId != null) {
            contextCache.remove(sessionId);
        }
    }

    // ==================== 路由上下文读写（L1 + L2） ====================

    /**
     * 加载路由上下文：L1 缓存 → L2 MySQL
     */
    private RoutingContext loadContext(String sessionId) {
        // L1: 内存缓存
        RoutingContext cached = contextCache.get(sessionId);
        if (cached != null) {
            return cached;
        }

        // L2: MySQL
        try {
            ChatConversationDO conv = chatConversationMapper.selectOne(
                    new LambdaQueryWrapper<ChatConversationDO>()
                            .eq(ChatConversationDO::getConversationId, sessionId)
                            .select(ChatConversationDO::getLastRoute, ChatConversationDO::getLastRouteMsg)
                            .last("LIMIT 1")
            );
            if (conv != null && conv.getLastRoute() != null) {
                RoutingContext ctx = new RoutingContext(conv.getLastRoute(), conv.getLastRouteMsg());
                contextCache.put(sessionId, ctx); // 回填 L1
                return ctx;
            }
        } catch (Exception e) {
            log.warn("加载路由上下文失败: sessionId={}, error={}", sessionId, e.getMessage());
        }

        return null;
    }

    /**
     * 保存路由上下文：双写 L1 内存 + L2 MySQL
     */
    private void saveContext(String sessionId, String route, String message) {
        // L1: 内存缓存
        contextCache.put(sessionId, new RoutingContext(route, message));

        // L2: MySQL（只更新路由相关字段，不影响其他字段）
        try {
            int rows = chatConversationMapper.update(null,
                    new LambdaUpdateWrapper<ChatConversationDO>()
                            .eq(ChatConversationDO::getConversationId, sessionId)
                            .set(ChatConversationDO::getLastRoute, route)
                            .set(ChatConversationDO::getLastRouteMsg, truncate(message, 500))
            );
            if (rows == 0) {
                log.debug("路由上下文保存跳过: 会话不存在 sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("保存路由上下文失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 截断字符串到指定最大长度
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    // ==================== 响应解析 ====================

    private RouteResult parseRouteResponse(String response) {
        if (response == null || response.isBlank()) {
            return RouteResult.defaultRoute();
        }

        try {
            String json = extractJson(response);
            JSONObject obj = JSONUtil.parseObj(json);

            String route = obj.getStr("route", "general");
            double confidence = obj.getDouble("confidence", 0.0);
            String reason = obj.getStr("reason", "未提供原因");

            return new RouteResult(route, confidence, reason);

        } catch (Exception e) {
            log.warn("解析路由响应失败: response={}, error={}", response, e.getMessage());
            return RouteResult.defaultRoute();
        }
    }

    private String extractJson(String text) {
        String trimmed = text.strip();

        int jsonBlockStart = trimmed.indexOf("```json");
        if (jsonBlockStart != -1) {
            int jsonStart = trimmed.indexOf("\n", jsonBlockStart);
            int jsonEnd = trimmed.indexOf("```", jsonStart + 1);
            if (jsonStart != -1 && jsonEnd != -1) {
                return trimmed.substring(jsonStart + 1, jsonEnd).strip();
            }
        }

        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart != -1) {
            int jsonStart = trimmed.indexOf("\n", codeBlockStart);
            int jsonEnd = trimmed.indexOf("```", jsonStart + 1);
            if (jsonStart != -1 && jsonEnd != -1) {
                return trimmed.substring(jsonStart + 1, jsonEnd).strip();
            }
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return trimmed;
    }

    /**
     * 路由上下文（轻量级，只保留最近一轮的路由和消息）
     */
    private record RoutingContext(String route, String lastMessage) {}
}
