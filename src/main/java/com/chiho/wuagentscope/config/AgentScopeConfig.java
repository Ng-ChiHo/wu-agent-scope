package com.chiho.wuagentscope.config;

import com.chiho.wuagentscope.tools.ImageSearchTool;
import com.chiho.wuagentscope.tools.ChartSuggestTool;
import com.chiho.wuagentscope.tools.SchemaInspectorTool;
import com.chiho.wuagentscope.tools.SqlExecuteTool;
import com.chiho.wuagentscope.tools.TimeTool;
import com.chiho.wuagentscope.tools.TokenUsageTool;
import com.chiho.wuagentscope.tools.WebReaderTool;
import com.chiho.wuagentscope.tools.WebSearchTool;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;

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
@Profile("!harness")
@DependsOn("openTelemetry")
public class AgentScopeConfig {

    // MySQL 状态存储database
    private static final String DB_NAME = "agent_scope";
    // MySQL 状态储存table
    private static final String TABLE_NAME = "agent_state";

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
                           WebSearchTool webSearchTool, WebReaderTool webReaderTool,
                           TokenUsageTool tokenUsageTool, SchemaInspectorTool schemaInspectorTool,
                           SqlExecuteTool sqlExecuteTool, ChartSuggestTool chartSuggestTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(timeTool);
        toolkit.registerTool(imageSearchTool);
        toolkit.registerTool(webSearchTool);
        toolkit.registerTool(webReaderTool);
        toolkit.registerTool(tokenUsageTool);
        toolkit.registerTool(schemaInspectorTool);
        toolkit.registerTool(sqlExecuteTool);
        toolkit.registerTool(chartSuggestTool);
        return toolkit;
    }

    /**
     * 配置 Skill 文件系统仓库
     * <p>
     * 从项目根目录下的 skills/ 目录加载 Skill。
     * 每个子目录包含一个 SKILL.md 文件即为一个 Skill。
     * 支持热加载：DynamicSkillMiddleware 会监听文件变化并自动重新加载。
     */
    @Bean
    public FileSystemSkillRepository skillRepository(
            @Value("${agentscope.skills.dir:skills}") String skillsDir) throws IOException {
        Path skillsPath = Path.of(skillsDir);
        // 如果是相对路径，基于项目根目录解析
        if (!skillsPath.isAbsolute()) {
            skillsPath = Path.of(System.getProperty("user.dir")).resolve(skillsDir);
        }
        return new FileSystemSkillRepository(skillsPath, false);
    }

    /**
     * 配置动态 Skill 中间件
     * <p>
     * DynamicSkillMiddleware 实现了 MiddlewareBase，在 onSystemPrompt 阶段
     * 将 Skill 内容注入到系统提示词中。
     * <p>
     * 工作流程：
     * 1. 从 FileSystemSkillRepository 加载所有 Skill
     * 2. 计算 Skill 内容签名，变化时重新加载
     * 3. 将 Skill 描述注入 system prompt
     * 4. 将 Skill 绑定的工具注册到 Toolkit
     */
    @Bean
    public DynamicSkillMiddleware dynamicSkillMiddleware(
            FileSystemSkillRepository skillRepository, Toolkit toolkit) {
        return new DynamicSkillMiddleware(
                java.util.List.of(skillRepository), toolkit);
    }

}
