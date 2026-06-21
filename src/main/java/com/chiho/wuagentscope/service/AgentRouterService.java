package com.chiho.wuagentscope.service;

import com.chiho.wuagentscope.model.RouteResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * 意图路由服务
 * <p>
 * 使用轻量级 LLM 模型（qwen3-vl:8b）对用户消息进行意图分类，
 * 判断应由哪个模块处理，返回路由结果。
 *
 * @author ChiHo
 */
@Service
@Profile("!harness")
@Slf4j
public class AgentRouterService {

    private static final String ROUTER_SYSTEM_PROMPT = String.join("\n",
            "你是一个意图分类系统。根据用户消息，判断应该由哪个模块处理。",
            "",
            "可选路由：",
            "- general: 通用对话、闲聊、简单问答、翻译、总结、知识问答、编程帮助",
            "- data_analyst: 数据查询、SQL、报表、统计分析、图表可视化、数据库相关问题",
            "",
            "只输出 JSON，不要输出其他内容：",
            "{\"route\": \"general\", \"confidence\": 0.95, \"reason\": \"用户在闲聊\"}");

    private static final double CONFIDENCE_THRESHOLD = 0.8;

    private static final String ROUTER_AGENT_NAME = "router-classifier";

    private final AgentStateStore agentStateStore;
    private final Toolkit toolkit;

    private ReActAgent routerAgent;

    public AgentRouterService(AgentStateStore agentStateStore, Toolkit toolkit) {
        this.agentStateStore = agentStateStore;
        this.toolkit = toolkit;
    }

    @PostConstruct
    public void init() {
        OllamaChatModel model = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen3-vl:8b")
                .formatter(new OllamaChatFormatter())
                .build();

        routerAgent = ReActAgent.builder()
                .name(ROUTER_AGENT_NAME)
                .sysPrompt(ROUTER_SYSTEM_PROMPT)
                .model(model)
                .stateStore(agentStateStore)
                .toolkit(toolkit)
                .maxIters(1)
                .build();

        log.info("路由器 Agent 初始化完成: model=qwen3-vl:8b, confidenceThreshold={}", CONFIDENCE_THRESHOLD);
    }

    /**
     * 对用户消息进行意图分类路由
     *
     * @param userMessage 用户输入的消息
     * @return 路由分类结果，置信度低于阈值时回退为 general
     */
    public RouteResult route(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return RouteResult.defaultRoute();
        }

        try {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("router-system")
                    .sessionId("router-session")
                    .build();

            UserMessage msg = new UserMessage(userMessage);
            String response = routerAgent.call(java.util.List.of(msg), ctx)
                    .block()
                    .getTextContent();

            RouteResult result = parseRouteResponse(response);

            if (result.confidence() < CONFIDENCE_THRESHOLD) {
                log.debug("路由置信度不足: confidence={}, reason={}, 回退到 general",
                        result.confidence(), result.reason());
                return new RouteResult("general", result.confidence(), result.reason());
            }

            log.debug("路由分类结果: route={}, confidence={}, reason={}",
                    result.route(), result.confidence(), result.reason());
            return result;

        } catch (Exception e) {
            log.error("路由分类异常: message={}, error={}", userMessage, e.getMessage(), e);
            return RouteResult.defaultRoute();
        }
    }

    /**
     * 解析路由 Agent 返回的 JSON 响应
     * <p>
     * 响应可能包含 markdown 代码块包装（如 ```json ... ```），需要提取其中的 JSON。
     */
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

    /**
     * 从响应文本中提取 JSON 字符串
     * <p>
     * 处理 LLM 可能返回的 markdown 代码块格式：
     * ```json
     * {"route": "general", "confidence": 0.95, "reason": "..."}
     * ```
     */
    private String extractJson(String text) {
        String trimmed = text.strip();

        // 尝试提取 markdown 代码块中的 JSON
        int jsonBlockStart = trimmed.indexOf("```json");
        if (jsonBlockStart != -1) {
            int jsonStart = trimmed.indexOf("\n", jsonBlockStart);
            int jsonEnd = trimmed.indexOf("```", jsonStart + 1);
            if (jsonStart != -1 && jsonEnd != -1) {
                return trimmed.substring(jsonStart + 1, jsonEnd).strip();
            }
        }

        // 尝试提取普通代码块中的 JSON
        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart != -1) {
            int jsonStart = trimmed.indexOf("\n", codeBlockStart);
            int jsonEnd = trimmed.indexOf("```", jsonStart + 1);
            if (jsonStart != -1 && jsonEnd != -1) {
                return trimmed.substring(jsonStart + 1, jsonEnd).strip();
            }
        }

        // 尝试直接查找 JSON 对象（以 { 开头，以 } 结尾）
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return trimmed;
    }
}
