# 数据分析 + 智能路由 设计文档

> 日期：2026-06-21
> 状态：设计完成，待实施

## 概述

为 wu-agent-scope 项目新增两大能力：
1. **数据分析**：用户用中文提问，Agent 自动编写 SQL 查询数据库，返回 ECharts 图表
2. **智能路由**：Router Agent 分析用户意图，自动路由到最合适的专业 Agent

## 架构方案

采用 **方案 B：多 Agent + Router**，一步到位。

### 整体架构

```
                          ┌─────────────────┐
                          │   前端 (Vue 3)    │
                          │  ECharts + SSE   │
                          └────────┬────────┘
                                   │
                          ┌────────▼────────┐
                          │  AiController    │
                          │  POST /ai/chat   │
                          └────────┬────────┘
                                   │
                          ┌────────▼────────┐
                          │   ChatService    │
                          │  (改造：加 Router) │
                          └────────┬────────┘
                                   │
                          ┌────────▼────────┐
                          │  AgentRouterSvc  │
                          │  qwen3-vl:8b 分类 │
                          │  输出: route type │
                          └────────┬────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
     ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
     │ general Agent │    │data_analyst  │    │  (future)    │
     │  (现有 Agent)  │    │   Agent      │    │ knowledge    │
     │ group:        │    │ group:       │    │ code ...     │
     │  general      │    │  general+data│    │              │
     └──────────────┘    └──────────────┘    └──────────────┘
              │                    │
     ┌────────▼────────┐  ┌───────▼────────┐
     │ 共享 Toolkit     │  │ 共享 Toolkit    │
     │ Tool Groups:    │  │ Tool Groups:   │
     │  general (激活)  │  │  general (激活) │
     │  data (未激活)   │  │  data (激活)    │
     └─────────────────┘  └────────────────┘
```

### 关键设计决策

1. **Router 作为 Service 层**，不是独立 Agent 实例 — 只做分类，不参与对话
2. **每个专业 Agent 是独立的 ReActAgent 实例**，有各自的 system prompt
3. **共享同一个 Toolkit**，通过 Tool Groups 控制每个 Agent 能看到哪些 Tools
4. **共享同一个 AgentStateStore**，对话历史在 Agent 间透明切换
5. **Router 用 qwen3-vl:8b 轻量模型**做意图分类
6. **模型选择与 Agent 路由独立** — 用户手动选模型，Router 自动选 Agent

## 数据分析 Tools（第 1、2 步）

### Tool 1: SchemaInspectorTool

**职责：** 查询数据库表结构，让 Agent 了解可用的表和字段。

```java
@Tool(name = "inspect_database_schema",
      description = "当用户询问数据、报表、统计相关问题时，先调用此工具获取数据库表结构。" +
                   "不传参数返回所有表概览，传入表名返回该表的字段详情。")
public String inspectSchema(
    @ToolParam(name = "table_name", description = "表名，可选。不传则返回所有表列表") String tableName)
```

**输出格式：**

```json
// 不传参：表列表
{"tables": ["ai_user", "ai_chat_conversation", "agent_call_log", "agent_state"]}

// 传表名：字段详情
{
  "table": "agent_call_log",
  "columns": [
    {"name": "id", "type": "bigint", "comment": "主键"},
    {"name": "user_id", "type": "bigint", "comment": "用户ID"},
    {"name": "model_name", "type": "varchar", "comment": "模型名称"},
    {"name": "input_tokens", "type": "int", "comment": "输入token数"}
  ]
}
```

**实现要点：**
- 查询 `information_schema.COLUMNS` 获取字段信息
- 只暴露 `agent_scope` 库的表（安全边界）
- 后续扩展多数据源时，加 `datasource` 参数

### Tool 2: SqlExecuteTool

**职责：** 执行只读 SQL 查询，返回结果 JSON。

```java
@Tool(name = "execute_sql_query",
      description = "当需要查询数据库获取数据时，调用此工具执行 SELECT 语句。" +
                   "仅支持 SELECT，禁止任何写操作。返回查询结果 JSON 数组。")
public String executeQuery(
    @ToolParam(name = "sql", description = "要执行的 SELECT SQL 语句") String sql)
```

**安全机制：**
- SQL 白名单校验：只允许 SELECT
- 关键字黑名单：INSERT / UPDATE / DELETE / DROP / ALTER / TRUNCATE / CREATE / GRANT
- 自动加 LIMIT（默认 1000 行）
- 只读事务
- 超时控制（30s）

**输出格式：**

```json
{
  "columns": ["product_name", "total_sales"],
  "rows": [
    ["产品A", 1200000],
    ["产品B", 980000]
  ],
  "row_count": 2,
  "execution_time_ms": 45
}
```

### Tool 3: ChartSuggestTool

**职责：** 分析数据特征，推荐图表类型，生成 ECharts 配置。

```java
@Tool(name = "suggest_chart_config",
      description = "当查询结果需要可视化展示时，调用此工具分析数据特征并生成 ECharts 图表配置。" +
                   "返回 ECharts option JSON，前端可直接渲染。")
public String suggestChart(
    @ToolParam(name = "data", description = "查询结果 JSON（columns + rows）") String data,
    @ToolParam(name = "question", description = "用户的原始问题") String question)
```

**图表类型推断逻辑：**

| 数据特征 | 推荐图表 |
|---------|---------|
| 1 维度 + 1 指标，行数 ≤ 10 | 饼图 / 柱状图 |
| 1 维度 + 1 指标，行数 > 10 | 柱状图（横向） |
| 时间维度 + 1 指标 | 折线图 |
| 2 维度 + 1 指标 | 分组柱状图 |
| 多指标对比 | 雷达图 / 多轴图 |

**输出格式：**

```json
{
  "chartType": "bar",
  "title": "上个月各产品销售额排名",
  "echartsOption": {
    "xAxis": {"type": "category", "data": ["产品A", "产品B"]},
    "yAxis": {"type": "value"},
    "series": [{"type": "bar", "data": [1200000, 980000]}]
  },
  "summary": "产品A销售额最高，达到120万，比第二名高出22%"
}
```

### 前端图表渲染

**后端 MessageVO 新增字段：**

```java
public class MessageVO {
    // ... 现有字段
    private String chartData;  // ChartSuggestTool 返回的 ECharts JSON
}
```

**前端判断逻辑：**

```javascript
if (message.chartData) {
  echarts.init(container).setOption(JSON.parse(message.chartData));
} else {
  // 普通文本展示
}
```

## Router + 多 Agent（第 3、4 步）

### AgentRouterService

**职责：** 调用轻量模型做意图分类，返回路由结果。

```java
@Service
public class AgentRouterService {

    private final ReActAgent routerAgent;  // qwen3-vl:8b

    private static final String ROUTER_PROMPT = """
        你是一个意图分类系统。根据用户消息，判断应该由哪个模块处理。

        可选路由：
        - general: 通用对话、闲聊、简单问答、翻译、总结
        - data_analyst: 数据查询、SQL、报表、统计分析、图表可视化

        只输出 JSON，不要输出其他内容：
        {"route": "general", "confidence": 0.95, "reason": "用户在闲聊"}
        """;

    public RouteResult route(String userMessage) {
        String result = routerAgent.call(userMessage);
        return parseRouteResult(result);
    }
}
```

**路由策略：**
- confidence ≥ 0.8 → 直接路由到对应 Agent
- confidence < 0.8 → 路由到 general Agent（保守策略）

### SpecialistAgentRegistry

**职责：** 管理专业 Agent 实例的创建和查找。保留现有 ModelAgentRegistry 供模型选择。

```java
@Component
public class SpecialistAgentRegistry {

    private final ModelAgentRegistry modelRegistry;
    private final Map<String, ReActAgent> agentCache = new ConcurrentHashMap<>();

    public ReActAgent getAgent(String route, String modelId) {
        return agentCache.computeIfAbsent(route + ":" + modelId, key -> {
            OllamaChatModel model = modelRegistry.getModel(modelId);
            return buildAgent(route, model);
        });
    }

    private ReActAgent buildAgent(String route, OllamaChatModel model) {
        return switch (route) {
            case "data_analyst" -> buildDataAnalystAgent(model);
            default -> buildGeneralAgent(model);
        };
    }

    private ReActAgent buildDataAnalystAgent(OllamaChatModel model) {
        return ReActAgent.builder()
            .name("DataAnalyst")
            .model(model)
            .systemPrompt(DATA_ANALYST_PROMPT)
            .toolkit(sharedToolkit)
            .activatedToolGroups(List.of("general", "data"))
            .middleware(contextTrimMiddleware, dynamicSkillMiddleware)
            .build();
    }
}
```

### ChatService 改造

```java
public Flux<String> chatStream(Long userId, String sessionId,
                                String message, String modelId) {
    // 1. Router 分类
    RouteResult route = routerService.route(message);

    // 2. 选择专业 Agent（传入用户选定的 modelId）
    ReActAgent agent = agentRegistry.getAgent(route.route(), modelId);

    // 3. 构建上下文并调用
    RuntimeContext ctx = buildContext(userId, sessionId);
    UserMessage userMsg = buildUserMessage(message, ...);
    return agent.streamEvents(List.of(userMsg), ctx)
        .filter(event -> event.getType() == EventType.TEXT_BLOCK_DELTA)
        .map(AgentEvent::getText);
}
```

### Tool Groups 注册

```java
// AgentScopeConfig.toolkit() 改造
@Bean
public Toolkit toolkit(...) {
    Toolkit toolkit = new Toolkit();

    toolkit.createToolGroup("general", "通用工具", true);
    toolkit.createToolGroup("data", "数据分析工具", true);

    toolkit.registration().tool(timeTool).group("general").apply();
    toolkit.registration().tool(webSearchTool).group("general").apply();
    toolkit.registration().tool(webReaderTool).group("general").apply();
    toolkit.registration().tool(imageSearchTool).group("general").apply();
    toolkit.registration().tool(tokenUsageTool).group("general").apply();

    toolkit.registration().tool(schemaInspectorTool).group("data").apply();
    toolkit.registration().tool(sqlExecuteTool).group("data").apply();
    toolkit.registration().tool(chartSuggestTool).group("data").apply();

    return toolkit;
}
```

### Agent 切换时的上下文处理

在 system prompt 中加入路由切换提示，让当前 Agent 知道用户之前在和哪个 Agent 对话。

`lastRoute` 存储在 `agent_state` 表的 `metadata` JSON 字段中（AgentState 已有此字段），通过 `RuntimeContext` 读写：

```java
// 读取上一次路由
String lastRoute = (String) ctx.getAgentState().getMetadata().get("lastRoute");

// 本次路由完成后写入
ctx.getAgentState().getMetadata().put("lastRoute", currentRoute);
```

ChatService 在调用 Agent 前注入路由切换提示：

```java
private String buildRouteAwarePrompt(String basePrompt, String previousRoute, String currentRoute) {
    if (previousRoute != null && !previousRoute.equals(currentRoute)) {
        return basePrompt + "\n\n注意：用户之前在和「" + previousRoute + "」模块对话，" +
               "请理解上下文后继续回答。如果问题不属于你的领域，请友好地告知用户。";
    }
    return basePrompt;
}
```

## 实施顺序

### 第 1 步：数据分析 Tools（1-2 天）

**新增：**
- `tools/SchemaInspectorTool.java`
- `tools/SqlExecuteTool.java`
- `tools/ChartSuggestTool.java`

**修改：**
- `AgentScopeConfig.java` — toolkit() 注册 3 个新 Tool，创建 "data" Tool Group
- `HarnessAgentConfig.java` — harnessToolkit() 同步注册

### 第 2 步：前端图表渲染（2-3 天）

**新增（前端）：**
- `src/components/ChartRenderer.vue`
- `src/utils/chartParser.js`

**修改：**
- `MessageVO.java` — 新增 chartData 字段
- `ChatConversationService.java` — toMessageVO() 解析图表数据
- `ChatMessage.vue` — 判断 chartData，展示图表或文本
- `package.json` — 添加 echarts 依赖

### 第 3 步：Router + 多 Agent（2-3 天）

**新增：**
- `service/AgentRouterService.java`
- `config/SpecialistAgentRegistry.java`
- `model/RouteResult.java`
- `skills/data-analyst/SKILL.md`

**修改：**
- `ChatService.java` — 接入 Router，调用 SpecialistAgentRegistry
- `AiController.java` — modelId 参数变为可选
- `AgentScopeConfig.java` — Toolkit 改用 Tool Groups 注册
- `ModelAgentRegistry.java` — 保留，供 SpecialistAgentRegistry 引用模型

### 第 4 步：按需扩展（后续）

每次新增专业 Agent 只需：
1. `SpecialistAgentRegistry` 中添加 Agent 构建方法
2. 创建对应的 `skills/<agent-name>/SKILL.md`
3. 如有新 Tool，注册到对应 Tool Group
4. `AgentRouterService` 的 prompt 中添加新路由选项

## 依赖引入

**后端 pom.xml：** 无需新增依赖（现有 MyBatis-Plus + AgentScope 已足够）

**前端 package.json：**
```json
{
  "echarts": "^5.5.0"
}
```

## 安全边界

- `SchemaInspectorTool` 只暴露 `agent_scope` 库的表
- `SqlExecuteTool` 只允许 SELECT，关键字黑名单 + 自动 LIMIT + 只读事务 + 超时
- Router 分类置信度 < 0.8 时回退到 general Agent
