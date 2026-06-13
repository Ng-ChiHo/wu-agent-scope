package com.chiho.wuagentscope.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

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
public class AgentScopeConfig {

    // MySQL 状态存储database
    private static final String DB_NAME = "agent_scope";
    // MySQL 状态储存table
    private static final String TABLE_NAME = "agent_state";

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
    public ReActAgent reActAgent(OllamaChatModel model, AgentStateStore stateStore) {
        return ReActAgent.builder()
                .name("common-chat")
                .sysPrompt("你是高情商、专业靠谱的智能助手，待人友好、逻辑清晰、回答通俗接地气。\n" +
                        "能深度思考、上下文连贯、主动追问模糊需求。\n" +
                        "客观中立不误导，复杂内容分点说明，排版清爽适合手机阅读，坚守合规底线，全场景耐心解答用户所有问题。")
                .model(model)
                .stateStore(stateStore)
                .maxIters(20)  // ReAct 循环最大迭代次数
                .build();
    }
}
