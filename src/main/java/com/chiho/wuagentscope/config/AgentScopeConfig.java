package com.chiho.wuagentscope.config;

import com.chiho.wuagentscope.middleware.ContextTrimMiddleware;
import com.chiho.wuagentscope.tools.ImageSearchTool;
import com.chiho.wuagentscope.tools.TimeTool;
import com.chiho.wuagentscope.tools.WebReaderTool;
import com.chiho.wuagentscope.tools.WebSearchTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AgentScope 核心配置
 * <p>
 * 使用 ReActAgent（裸推理-行动循环引擎），会话状态持久化到 MySQL。
 * <p>
 * ReActAgent 核心特性：
 * - 无状态设计：Agent 实例只持有不可变配置（sysPrompt、model、toolkit、middlewares）
 * - 会话持久化：通过 AgentStateStore 按 (userId, sessionId) 自动加载/保存对话状态
 * - 多用户并发：同 (userId, sessionId) 串行，不同 session 完全并行
 * - 推理-行动循环：call() 触发完整的 think → act → observe 循环
 * <p>
 * AgentStateStore 等价于 Spring AI 的 ChatMemory，但存储范围更广：
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  Spring AI ChatMemory          AgentScope AgentStateStore          │
 * │  ──────────────────────         ──────────────────────────         │
 * │  只存消息历史                    存储完整 AgentState：               │
 * │  add(conversationId, msgs)      save(userId, sessionId, key, state)│
 * │  get(conversationId)            get(userId, sessionId, key, class) │
 * │  clear(conversationId)          delete(userId, sessionId)          │
 * │                                 ├─ context()      → 对话历史       │
 * │  需要 Advisor 手动注入           ├─ summary()      → 压缩摘要       │
 * │  到 prompt                      ├─ permission     → 工具权限       │
 * │                                 └─ planMode/tasks → 计划/任务      │
 * │                                 ReActAgent 在 call() 入口自动加载   │
 * └─────────────────────────────────────────────────────────────────────┘
 * @author ChiHo
 */
@Configuration
@DependsOn("openTelemetry")
public class AgentScopeConfig {

    // MySQL 状态存储database
    private static final String DB_NAME = "agent_scope";
    // MySQL 状态储存table
    private static final String TABLE_NAME = "agent_state";

    /** Agent 人格提示词（固定部分） */
    private static final String PERSONA_PROMPT = String.join("\n",
            "你是高情商、专业靠谱的智能助手，待人友好、逻辑清晰、回答通俗接地气。",
            "能深度思考、上下文连贯、主动追问模糊需求。",
            "客观中立不误导，复杂内容分点说明，排版清爽适合手机阅读，坚守合规底线，全场景耐心解答用户所有问题。");

    /** 工具使用通用规则（固定部分，适用于所有工具） */
    private static final String TOOL_USAGE_RULES = String.join("\n",
            "",
            "## 工具使用规则",
            "你拥有一系列工具，遇到对应场景时必须主动调用，不要凭记忆回答。",
            "判断依据：如果用户的问题涉及实时数据、最新事件、外部信息查询、或你不确定的事实，就必须先调用相关工具获取信息再回答。",
            "严禁在需要工具辅助的场景下凭记忆编造答案。");

    /**
     * 配置 Ollama 本地模型
     * <p>
     * OllamaChatModel 连接本地 Ollama 服务（默认 http://localhost:11434），
     * 使用 qwen3:14b 模型。
     * <p>
     * Formatter 负责将 AgentScope 的 Msg 对象转换为 Ollama API 期望的请求载荷。
     * 切换模型只需改 modelName，如 "llama3"、"deepseek-r1:14b" 等。
     */
    @Bean
    public OllamaChatModel ollamaChatModel(
            @Value("${agentscope.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${agentscope.ollama.model-name:qwen3:14b}") String modelName) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .formatter(new OllamaChatFormatter())
                .build();
    }

    /**
     * 配置 MySQL 状态存储（企业级）
     * <p>
     * MysqlAgentStateStore 将每个 (userId, sessionId) 的完整 AgentState 持久化到 MySQL。
     * <p>
     * 构造函数：
     * - MysqlAgentStateStore(DataSource)                    → 默认表名，自动建表
     * - MysqlAgentStateStore(DataSource, autoCreate)        → 控制是否自动建表
     * - MysqlAgentStateStore(DataSource, dbName, tableName, autoCreate) → 自定义库名/表名
     * <p>
     * 与 JsonFileAgentStateStore 的区别：
     * - 支持多副本共享（多个 JVM 实例连同一个 MySQL）
     * - 支持 SQL 查询、审计、报表
     * - 适合生产环境部署
     */
    @Bean
    public AgentStateStore agentStateStore(DataSource dataSource) {
        // 第二个参数 true = 自动创建表（如果不存在）
        return new MysqlAgentStateStore(dataSource, DB_NAME, TABLE_NAME, true);
    }

    /**
     * 统一配置 Toolkit
     * <p>
     * Toolkit 负责将工具注册到 AgentScope，AgentScope 会自动将工具名称注册到模型。
     * 模型通过工具名称调用工具，返回 JSON 格式结果给 AgentScope，AgentScope 解析结果并调用对应工具。
     * <p>
     * 例如，注册了 TimeTool 后，模型可以通过 "getCurrentDateTime()" 调用，返回当前时间。
     * <p>
     * 注册工具时，工具名称不能与模型的内置函数名称重复，否则会覆盖内置函数。
     * @param timeTool
     * @return
     */
    @Bean
    public Toolkit toolkit(TimeTool timeTool, ImageSearchTool imageSearchTool,
                           WebSearchTool webSearchTool, WebReaderTool webReaderTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(timeTool);
        toolkit.registerTool(imageSearchTool);
        toolkit.registerTool(webSearchTool);
        toolkit.registerTool(webReaderTool);
        return toolkit;
    }

    /**
     * 配置 ReActAgent（裸推理-行动循环引擎）
     * <p>
     * 核心能力：
     * 1. 对话持久化 —— 相同 (userId, sessionId) 的第二次 call() 自动恢复上次状态
     * 2. 多用户并发 —— 同一实例可服务多个用户，不同 session 完全并行，同 session 自动串行
     * 3. ReAct 循环 —— 模型推理 → 工具调用 → 观察结果 → 继续推理，直到完成
     * <p>
     * 切换模型只需改 .model(...) 字符串前缀：
     * - "ollama:qwen3:14b" —— 本地 Ollama
     * - "dashscope:qwen-plus" —— 阿里云 DashScope
     * - "openai:gpt-4o" —— OpenAI
     * - "anthropic:claude-sonnet-4-5" —— Anthropic
     */
    @Bean
    public ReActAgent reActAgent(OllamaChatModel model, AgentStateStore stateStore,
                                  Toolkit toolkit,
                                  ContextTrimMiddleware contextTrimMiddleware,
                                  OtelTracingMiddleware otelTracingMiddleware) {
        return ReActAgent.builder()
                .name("common-chat")
                .sysPrompt(buildSystemPrompt(toolkit))
                .model(model)
                .stateStore(stateStore)
                .toolkit(toolkit)
                .middleware(contextTrimMiddleware)   // 先截断上下文
                .middleware(otelTracingMiddleware)    // 再记录追踪
                .maxIters(20)  // ReAct 循环最大迭代次数
                .build();
    }

    /**
     * 动态构建系统提示词
     * <p>
     * 工具部分从 Toolkit 的 ToolSchema 自动提取，新增工具只需注册到 Toolkit 即可，
     * 无需手动修改系统提示词。
     *
     * @param toolkit 已注册所有工具的 Toolkit
     * @return 完整的系统提示词
     */
    private String buildSystemPrompt(Toolkit toolkit) {
        String toolSection = buildToolPrompt(toolkit);
        return PERSONA_PROMPT + "\n\n" + toolSection + "\n\n" + TOOL_USAGE_RULES;
    }

    /**
     * 从 Toolkit 动态生成工具说明
     * <p>
     * 遍历所有已注册的 ToolSchema，提取 name 和 description，
     * 格式化为 LLM 易读的列表。
     */
    private String buildToolPrompt(Toolkit toolkit) {
        List<ToolSchema> schemas = toolkit.getToolSchemas();
        if (schemas == null || schemas.isEmpty()) {
            return "";
        }

        String toolList = schemas.stream()
                .map(schema -> "- " + schema.getName() + ": " + schema.getDescription())
                .collect(Collectors.joining("\n"));

        return "## 可用工具\n" + toolList;
    }
}
