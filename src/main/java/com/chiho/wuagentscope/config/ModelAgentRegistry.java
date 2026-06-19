package com.chiho.wuagentscope.config;

import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.middleware.ContextTrimMiddleware;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心
 * <p>
 * 启动时根据 application.yml 中的 agentscope.models 配置，
 * 为每个模型创建独立的 ReActAgent 实例，运行时按 modelId 路由。
 * <p>
 * 所有 Agent 共享同一个 AgentStateStore（会话状态）和 Toolkit（工具集），
 * 只有底层 ChatModel 不同。
 * @author ChiHo
 */
@Configuration
@ConfigurationProperties(prefix = "agentscope.models")
@Data
@Slf4j
public class ModelAgentRegistry {

    /** 默认模型 ID */
    private String defaultModel;

    /** 可用模型配置列表 */
    private List<ModelConfig> available;

    /** modelId → ReActAgent 实例 */
    private final Map<String, ReActAgent> agentMap = new ConcurrentHashMap<>();

    /** modelId → ModelConfig（用于前端展示模型信息） */
    private final Map<String, ModelConfig> configMap = new ConcurrentHashMap<>();

    /** 由 AgentScopeConfig 注入的共享组件 */
    private final AgentStateStore agentStateStore;
    private final Toolkit toolkit;
    private final ContextTrimMiddleware contextTrimMiddleware;
    private final OtelTracingMiddleware otelTracingMiddleware;
    private final DynamicSkillMiddleware dynamicSkillMiddleware;

    public ModelAgentRegistry(AgentStateStore agentStateStore, Toolkit toolkit,
                              ContextTrimMiddleware contextTrimMiddleware,
                              OtelTracingMiddleware otelTracingMiddleware,
                              DynamicSkillMiddleware dynamicSkillMiddleware) {
        this.agentStateStore = agentStateStore;
        this.toolkit = toolkit;
        this.contextTrimMiddleware = contextTrimMiddleware;
        this.otelTracingMiddleware = otelTracingMiddleware;
        this.dynamicSkillMiddleware = dynamicSkillMiddleware;
    }

    @PostConstruct
    public void init() {
        if (available == null || available.isEmpty()) {
            log.warn("未配置任何可用模型（agentscope.models.available）");
            return;
        }

        for (ModelConfig config : available) {
            try {
                OllamaChatModel model = createOllamaModel(config);
                ReActAgent agent = ReActAgent.builder()
                        .name("chat-" + config.getId())
                        .sysPrompt(buildSystemPrompt(toolkit))
                        .model(model)
                        .stateStore(agentStateStore)
                        .toolkit(toolkit)
                        .middleware(contextTrimMiddleware)
                        .middleware(otelTracingMiddleware)
                        .middleware(dynamicSkillMiddleware)
                        .maxIters(config.getMaxIters())
                        .build();
                agentMap.put(config.getId(), agent);
                configMap.put(config.getId(), config);
                log.info("注册模型: id={}, provider={}, modelName={}, maxIters={}",
                        config.getId(), config.getProvider(), config.getModelName(), config.getMaxIters());
            } catch (Exception e) {
                log.error("注册模型失败: id={}, error={}", config.getId(), e.getMessage(), e);
            }
        }

        log.info("模型注册完成: 共 {} 个模型，默认模型={}", agentMap.size(), defaultModel);
    }

    /**
     * 获取指定模型的 ReActAgent
     */
    public ReActAgent getAgent(String modelId) {
        String resolved = modelId != null ? modelId : defaultModel;
        ReActAgent agent = agentMap.get(resolved);
        if (agent == null) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND, "不支持的模型: " + resolved);
        }
        return agent;
    }

    /**
     * 获取所有可用模型配置（用于前端展示）
     */
    public List<ModelConfig> getAvailableModels() {
        return available;
    }

    /**
     * 创建 Ollama 模型实例
     */
    private OllamaChatModel createOllamaModel(ModelConfig config) {
        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .formatter(new OllamaChatFormatter())
                .build();
    }

    /**
     * 构建系统提示词（复用 AgentScopeConfig 中的逻辑）
     */
    private String buildSystemPrompt(Toolkit toolkit) {
        String personaPrompt = String.join("\n",
                "你是高情商、专业靠谱的智能助手，待人友好、逻辑清晰、回答通俗接地气。",
                "能深度思考、上下文连贯、主动追问模糊需求。",
                "客观中立不误导，复杂内容分点说明，排版清爽适合手机阅读，坚守合规底线，全场景耐心解答用户所有问题。");

        String toolUsageRules = String.join("\n",
                "",
                "## 工具使用规则",
                "你拥有一系列工具，遇到对应场景时必须主动调用，不要凭记忆回答。",
                "判断依据：如果用户的问题涉及实时数据、最新事件、外部信息查询、或你不确定的事实，就必须先调用相关工具获取信息再回答。",
                "严禁在需要工具辅助的场景下凭记忆编造答案。");

        List<io.agentscope.core.model.ToolSchema> schemas = toolkit.getToolSchemas();
        String toolSection = "";
        if (schemas != null && !schemas.isEmpty()) {
            String toolList = schemas.stream()
                    .map(s -> "- " + s.getName() + ": " + s.getDescription())
                    .collect(java.util.stream.Collectors.joining("\n"));
            toolSection = "## 可用工具\n" + toolList;
        }

        return personaPrompt + "\n\n" + toolSection + "\n\n" + toolUsageRules;
    }
}
