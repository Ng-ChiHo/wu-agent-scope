package com.chiho.wuagentscope.config;

import com.chiho.wuagentscope.middleware.ContextTrimMiddleware;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 专业 Agent 注册中心
 * <p>
 * 根据路由（route）动态构建不同领域的专业 Agent，共享同一个底层 ChatModel。
 * 与 {@link ModelAgentRegistry} 的区别：
 * - ModelAgentRegistry：每个模型一个通用 Agent（全工具集）
 * - SpecialistAgentRegistry：按领域裁剪工具集，构建专业 Agent（如数据分析师）
 * <p>
 * Agent 实例按 "route:modelId" 缓存，避免重复创建。
 * @author ChiHo
 */
@Configuration
@Profile("!harness")
@Slf4j
public class SpecialistAgentRegistry {

    /** 数据分析师系统提示词 */
    private static final String DATA_ANALYST_PROMPT = String.join("\n",
            "你是一个专业的数据分析助手，擅长 SQL 查询和数据可视化。",
            "当用户询问数据相关问题时，你的工作流程是：",
            "1. 先用 inspect_database_schema 了解表结构",
            "2. 用 execute_sql_query 执行 SELECT 查询获取数据",
            "3. 如果用户需要图表展示，用 suggest_chart_config 生成 ECharts 配置",
            "4. 用简洁的中文总结查询结果和关键发现",
            "",
            "注意事项：",
            "- 只使用 SELECT 语句，不要尝试写操作",
            "- 复杂查询可以分步执行，先验证数据再汇总",
            "- 图表类型要根据数据特征合理选择",
            "- 如果用户的问题不涉及数据，直接用中文回答");

    private final ModelAgentRegistry modelRegistry;
    private final AgentStateStore agentStateStore;
    private final Toolkit toolkit;
    private final ContextTrimMiddleware contextTrimMiddleware;
    private final OtelTracingMiddleware otelTracingMiddleware;
    private final DynamicSkillMiddleware dynamicSkillMiddleware;

    /** 路由+模型维度的 Agent 缓存，key = "route:modelId" */
    private final ConcurrentHashMap<String, ReActAgent> agentCache = new ConcurrentHashMap<>();

    public SpecialistAgentRegistry(ModelAgentRegistry modelRegistry,
                                   AgentStateStore agentStateStore,
                                   Toolkit toolkit,
                                   ContextTrimMiddleware contextTrimMiddleware,
                                   OtelTracingMiddleware otelTracingMiddleware,
                                   DynamicSkillMiddleware dynamicSkillMiddleware) {
        this.modelRegistry = modelRegistry;
        this.agentStateStore = agentStateStore;
        this.toolkit = toolkit;
        this.contextTrimMiddleware = contextTrimMiddleware;
        this.otelTracingMiddleware = otelTracingMiddleware;
        this.dynamicSkillMiddleware = dynamicSkillMiddleware;
    }

    /**
     * 获取指定路由和模型的 Agent
     * <p>
     * 缓存 key 为 "route:modelId"，同一组合只创建一次。
     *
     * @param route   专业领域路由（如 "general"、"data-analyst"）
     * @param modelId 模型 ID（null 则使用默认模型）
     * @return 缓存的 ReActAgent 实例
     */
    public ReActAgent getAgent(String route, String modelId) {
        String resolvedModel = modelId != null ? modelId : "default";
        String cacheKey = route + ":" + resolvedModel;
        return agentCache.computeIfAbsent(cacheKey, key -> {
            log.info("构建专业 Agent: route={}, modelId={}", route, resolvedModel);
            return switch (route) {
                case "data-analyst" -> buildDataAnalystAgent(resolvedModel);
                default -> buildGeneralAgent(resolvedModel);
            };
        });
    }

    /**
     * 构建通用 Agent（全工具集），委托给 ModelAgentRegistry
     */
    private ReActAgent buildGeneralAgent(String modelId) {
        return modelRegistry.getAgent(modelId);
    }

    /**
     * 构建数据分析师 Agent（仅 data + general 工具集）
     */
    private ReActAgent buildDataAnalystAgent(String modelId) {
        OllamaChatModel model = modelRegistry.getModel(modelId);
        // 复制一份 Toolkit 并限制为 general + data 工具组
        Toolkit specialistToolkit = toolkit.copy();
        specialistToolkit.setActiveGroups(List.of("general", "data"));
        return ReActAgent.builder()
                .name("data-analyst")
                .sysPrompt(DATA_ANALYST_PROMPT)
                .model(model)
                .stateStore(agentStateStore)
                .toolkit(specialistToolkit)
                .middleware(contextTrimMiddleware)
                .middleware(otelTracingMiddleware)
                .middleware(dynamicSkillMiddleware)
                .maxIters(20)
                .build();
    }
}
