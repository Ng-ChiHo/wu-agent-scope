package com.chiho.wuagentscope.config;

import com.chiho.wuagentscope.middleware.ContextTrimMiddleware;
import com.chiho.wuagentscope.tools.ChartSuggestTool;
import com.chiho.wuagentscope.tools.ImageSearchTool;
import com.chiho.wuagentscope.tools.TimeTool;
import com.chiho.wuagentscope.tools.TokenUsageTool;
import com.chiho.wuagentscope.tools.WebReaderTool;
import com.chiho.wuagentscope.tools.WebSearchTool;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * HarnessAgent 实验性配置
 * <p>
 * 基于 AgentScope 2.0 的 HarnessAgent，包装现有 ReActAgent 并叠加工程级能力：
 * - 子 Agent 编排（研究专家、代码专家）
 * - 智能记忆管理（自动压缩、定期清理）
 * - Plan 模式（先规划再执行）
 * - 大结果自动卸载（Tool Result Eviction）
 * <p>
 * 激活方式：在 application-local.yml 中设置 spring.profiles.active: local,harness
 * <p>
 * 与 AgentScopeConfig 的关系：
 * - 激活 harness profile 时，本配置的 HarnessAgent 作为主 Agent（@Primary）
 * - 不激活时，AgentScopeConfig 的 ReActAgent 正常工作，零影响
 * - HarnessAgent 内部包装 ReActAgent，所有原有中间件、工具、状态存储完全保留
 *
 * @author ChiHo
 * @see AgentScopeConfig 原始 ReActAgent 配置
 */
@Configuration
@Profile("harness")
@DependsOn("openTelemetry")
public class HarnessAgentConfig {

    // ==================== 常量 ====================

    private static final String DB_NAME = "agent_scope";
    private static final String TABLE_NAME = "agent_state";

    /** Agent 人格提示词（与 AgentScopeConfig 保持一致） */
    private static final String PERSONA_PROMPT = String.join("\n",
            "你是高情商、专业靠谱的智能助手，待人友好、逻辑清晰、回答通俗接地气。",
            "能深度思考、上下文连贯、主动追问模糊需求。",
            "客观中立不误导，复杂内容分点说明，排版清爽适合手机阅读，坚守合规底线，全场景耐心解答用户所有问题。");

    /** 工具使用通用规则 */
    private static final String TOOL_USAGE_RULES = String.join("\n",
            "",
            "## 工具使用规则",
            "你拥有一系列工具，遇到对应场景时必须主动调用，不要凭记忆回答。",
            "判断依据：如果用户的问题涉及实时数据、最新事件、外部信息查询、或你不确定的事实，就必须先调用相关工具获取信息再回答。",
            "严禁在需要工具辅助的场景下凭记忆编造答案。");

    /** 子 Agent 使用规则 */
    private static final String SUBAGENT_USAGE_RULES = String.join("\n",
            "",
            "## 子 Agent 使用规则",
            "你可以调用以下专家子 Agent 来处理特定领域的任务：",
            "- 研究专家（researcher）：擅长信息检索、数据分析、报告撰写。当用户需要深度研究、对比分析、信息汇总时调用。",
            "- 代码专家（coder）：擅长代码编写、调试、Review。当用户需要写代码、分析代码、解决技术问题时调用。",
            "使用原则：",
            "1. 简单问题直接回答，不需要调用子 Agent",
            "2. 复杂任务可以拆分给多个子 Agent 并行处理",
            "3. 调用子 Agent 时，提供清晰的任务描述和上下文");

    // ==================== 基础 Bean（复用 AgentScopeConfig 的模式） ====================

    @Bean
    public OllamaChatModel harnessModel(
            @Value("${agentscope.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${agentscope.ollama.model-name:qwen3:14b}") String modelName) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .formatter(new OllamaChatFormatter())
                .build();
    }

    @Bean
    public AgentStateStore harnessStateStore(DataSource dataSource) {
        return new io.agentscope.extensions.mysql.state.MysqlAgentStateStore(
                dataSource, DB_NAME, TABLE_NAME, true);
    }

    @Bean
    public Toolkit harnessToolkit(TimeTool timeTool, ImageSearchTool imageSearchTool,
                                  WebSearchTool webSearchTool, WebReaderTool webReaderTool,
                                  TokenUsageTool tokenUsageTool, ChartSuggestTool chartSuggestTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(timeTool);
        toolkit.registerTool(imageSearchTool);
        toolkit.registerTool(webSearchTool);
        toolkit.registerTool(webReaderTool);
        toolkit.registerTool(tokenUsageTool);
        toolkit.registerTool(chartSuggestTool);
        return toolkit;
    }

    /**
     * 配置 Skill 文件系统仓库（harness 模式）
     */
    @Bean
    public FileSystemSkillRepository harnessSkillRepository(
            @Value("${agentscope.skills.dir:skills}") String skillsDir) throws java.io.IOException {
        java.nio.file.Path skillsPath = java.nio.file.Path.of(skillsDir);
        if (!skillsPath.isAbsolute()) {
            skillsPath = java.nio.file.Path.of(System.getProperty("user.dir")).resolve(skillsDir);
        }
        return new FileSystemSkillRepository(skillsPath, false);
    }

    // ==================== HarnessAgent 核心配置 ====================

    /**
     * 配置 HarnessAgent（工程级 Agent 运行时）
     * <p>
     * 核心特性：
     * 1. 包装 ReActAgent —— 保留原有推理-行动循环、中间件链、工具集
     * 2. 子 Agent 编排 —— 声明式定义专家 Agent，主 Agent 按需调用
     * 3. 智能记忆管理 —— 自动摘要、压缩、定期清理，解决长对话上下文爆炸
     * 4. Plan 模式 —— 复杂任务先规划再执行，只读工具可用
     * 5. 大结果卸载 —— 工具返回过长时自动存文件，替换为占位符
     * <p>
     * 禁用的模块（当前场景不需要）：
     * - 文件系统工具（无代码编辑需求）
     * - Shell 执行（安全考虑）
     * - 动态子 Agent（使用静态声明）
     * - 动态 Skill（暂不需要）
     * - Skill 管理工具（暂不需要）
     */
    @Bean
    @Primary
    public HarnessAgent harnessAgent(
            OllamaChatModel harnessModel,
            AgentStateStore harnessStateStore,
            Toolkit harnessToolkit,
            ContextTrimMiddleware contextTrimMiddleware,
            OtelTracingMiddleware otelTracingMiddleware,
            FileSystemSkillRepository harnessSkillRepository) {

        return HarnessAgent.builder()
                // ========== 基础配置（与 ReActAgent 一致） ==========
                .name("harness-chat")
                .sysPrompt(buildSystemPrompt(harnessToolkit))
                .model(harnessModel)
                .stateStore(harnessStateStore)
                .toolkit(harnessToolkit)
                .middleware(contextTrimMiddleware)
                .middleware(otelTracingMiddleware)
                .maxIters(20)

                // ========== 子 Agent 编排 ==========
                // 研究专家：擅长信息检索和分析
                .subagent(SubagentDeclaration.builder()
                        .name("researcher")
                        .description("研究专家，擅长信息检索、数据分析、报告撰写")
                        .model("ollama:qwen3:14b")
                        .steps(10)
                        .temperature(0.3)
                        .mode(SubagentDeclaration.Mode.SUBAGENT)
                        .workspaceMode(WorkspaceMode.SHARED)
                        .persistSession(true)
                        .inheritParentPermissions(true)
                        .hidden(false)
                        .exposeToUser(false)
                        .tools(List.of("web_search", "web_read", "search_image_tool"))
                        .build())
                // 代码专家：擅长代码编写和调试
                .subagent(SubagentDeclaration.builder()
                        .name("coder")
                        .description("代码专家，擅长代码编写、调试、技术方案设计")
                        .model("ollama:qwen3:14b")
                        .steps(10)
                        .temperature(0.2)
                        .mode(SubagentDeclaration.Mode.SUBAGENT)
                        .workspaceMode(WorkspaceMode.SHARED)
                        .persistSession(true)
                        .inheritParentPermissions(true)
                        .hidden(false)
                        .exposeToUser(false)
                        .tools(List.of("get_current_date_time"))
                        .build())

                // ========== 上下文压缩 ==========
                // 当消息数超过 40 条或 token 超过阈值时自动压缩
                .compaction(CompactionConfig.builder()
                        .triggerMessages(40)
                        .keepMessages(20)
                        .build())

                // ========== 大结果卸载 ==========
                // 工具返回超过 8000 字符时自动卸载到文件
                .toolResultEviction(ToolResultEvictionConfig.builder()
                        .maxResultChars(8000)
                        .previewChars(500)
                        .build())

                // ========== Plan 模式 ==========
                // 启用后，Agent 可以先进入规划模式，只读工具可用
                .enablePlanMode()

                // ========== 工作空间 ==========
                // 设置 Agent 的工作目录（用于 Plan 文件等）
                .workspace(Path.of(System.getProperty("java.io.tmpdir"), "agent-workspace"))

                // ========== Skill 配置 ==========
                .skillRepository(harnessSkillRepository)
                .skillsEnabled(true)

                // ========== 禁用不需要的模块 ==========
                .disableFilesystemTools()    // 无代码编辑需求
                .disableShellTool()          // 安全考虑
                .disableDynamicSubagents()   // 使用静态声明的子 Agent
                .disableMemoryTools()        // 禁用记忆工具（MemoryFlushManager 有 NPE bug）
                .disableMemoryHooks()        // 禁用记忆中间件（同上）

                // ========== 可观测性 ==========
                .enableAgentTracingLog(true)
                .environment("experimental")

                .build();
    }

    // ==================== 辅助方法 ====================

    /**
     * 动态构建系统提示词
     * <p>
     * 在原有工具说明基础上，增加子 Agent 使用规则。
     */
    private String buildSystemPrompt(Toolkit toolkit) {
        String toolSection = buildToolPrompt(toolkit);
        return PERSONA_PROMPT + "\n\n" + toolSection + "\n\n" + TOOL_USAGE_RULES + "\n\n" + SUBAGENT_USAGE_RULES;
    }

    /**
     * 从 Toolkit 动态生成工具说明
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
