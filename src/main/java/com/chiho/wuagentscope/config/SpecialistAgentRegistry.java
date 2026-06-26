package com.chiho.wuagentscope.config;

import com.chiho.wuagentscope.middleware.ContextTrimMiddleware;
import com.chiho.wuagentscope.middleware.ToolResultTrimMiddleware;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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

    /** 购车顾问系统提示词 */
    private static final String CAR_ADVISOR_PROMPT = String.join("\n",
            "你是一位专业购车顾问，拥有丰富的汽车知识和选车经验。专注汽车选购、车型评测、购车避坑、用车指导全场景服务。",
            "",
            "系统已自动为你检索购车知识库，检索结果以 <retrieved_knowledge> 标签注入。",
            "你必须优先基于检索结果中的专业数据为用户推荐车型，不要忽略检索结果。",
            "",
            "## 回答规范",
            "- 推荐车型时列出：车型名称、价格区间、核心优势、适合场景",
            "- 多款车型对比时用分点格式，便于阅读",
            "- 涉及预算时明确落地价范围",
            "- 涉及动力类型（燃油/混动/纯电）时说明各自优劣",
            "- 回答风格通俗接地气，少堆砌专业参数，用大白话解读优缺点、适配场景、潜在通病与购车陷阱，客观中立、不吹不黑、帮用户理性决策",
            "- 用户需求模糊时，主动极简引导询问预算、用途、车型、动力、品牌偏好，不敷衍、不推荐冷门杂牌车型，坚守专业靠谱、省心避坑的顾问定位",
            "",
            "## 禁止事项",
            "- 不要忽略检索结果中的推荐数据",
            "- 不要凭记忆编造车型推荐数据",
            "- 不要忽略用户的具体预算和用途需求",
            "- 不要推荐已停产或信息不确定的车型");

    private final ModelAgentRegistry modelRegistry;
    private final AgentStateStore agentStateStore;
    private final Toolkit toolkit;
    private final Toolkit carAdvisorToolkit;
    private final ContextTrimMiddleware contextTrimMiddleware;
    private final OtelTracingMiddleware otelTracingMiddleware;
    private final DynamicSkillMiddleware dynamicSkillMiddleware;
    private final GenericRAGHook genericRAGHook;
    private final ToolResultTrimMiddleware toolResultTrimMiddleware;

    /** 路由+模型维度的 Agent 缓存，key = "route:modelId" */
    private final ConcurrentHashMap<String, ReActAgent> agentCache = new ConcurrentHashMap<>();

    public SpecialistAgentRegistry(ModelAgentRegistry modelRegistry,
                                   AgentStateStore agentStateStore,
                                   Toolkit toolkit,
                                   @Qualifier("carAdvisorToolkit") Toolkit carAdvisorToolkit,
                                   ContextTrimMiddleware contextTrimMiddleware,
                                   OtelTracingMiddleware otelTracingMiddleware,
                                   DynamicSkillMiddleware dynamicSkillMiddleware,
                                   GenericRAGHook genericRAGHook,
                                   ToolResultTrimMiddleware toolResultTrimMiddleware) {
        this.modelRegistry = modelRegistry;
        this.agentStateStore = agentStateStore;
        this.toolkit = toolkit;
        this.carAdvisorToolkit = carAdvisorToolkit;
        this.contextTrimMiddleware = contextTrimMiddleware;
        this.otelTracingMiddleware = otelTracingMiddleware;
        this.dynamicSkillMiddleware = dynamicSkillMiddleware;
        this.genericRAGHook = genericRAGHook;
        this.toolResultTrimMiddleware = toolResultTrimMiddleware;
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
                case "data_analyst" -> buildDataAnalystAgent(resolvedModel);
                case "car_advisor" -> buildCarAdvisorAgent(resolvedModel);
                default -> buildGeneralAgent(resolvedModel);
            };
        });
    }

    /**
     * 构建通用 Agent（全工具集），委托给 ModelAgentRegistry
     */
    private ReActAgent buildGeneralAgent(String modelId) {
        return modelRegistry.getGeneralAgent(modelId);
    }

    /**
     * 构建数据分析师 Agent（共享 Toolkit，通过 system prompt 引导使用数据工具）
     */
    private ReActAgent buildDataAnalystAgent(String modelId) {
        OllamaChatModel model = modelRegistry.getModel(modelId);
        return ReActAgent.builder()
                .name("data-analyst")
                .sysPrompt(DATA_ANALYST_PROMPT)
                .model(model)
                .stateStore(agentStateStore)
                .toolkit(toolkit)
                .middleware(contextTrimMiddleware)
                .middleware(otelTracingMiddleware)
                .middleware(dynamicSkillMiddleware)
                .maxIters(20)
                .build();
    }

    /**
     * 构建购车顾问 Agent（精简工具集 + GenericRAGHook 自动检索 + 工具结果截断）
     */
    private ReActAgent buildCarAdvisorAgent(String modelId) {
        OllamaChatModel model = modelRegistry.getModel(modelId);
        return ReActAgent.builder()
                .name("car-advisor")
                .sysPrompt(CAR_ADVISOR_PROMPT)
                .model(model)
                .stateStore(agentStateStore)
                .toolkit(carAdvisorToolkit)        // 精简工具集：只有 retrieve_knowledge
                .hook(genericRAGHook)              // 自动检索知识库
                .middleware(contextTrimMiddleware)
                .middleware(toolResultTrimMiddleware) // 工具结果截断
                .middleware(otelTracingMiddleware)
                .maxIters(10)                       // 限制 ReAct 迭代次数
                .build();
    }
}
