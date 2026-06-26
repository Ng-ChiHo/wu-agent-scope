# wu-agent-scope

基于 **AgentScope Java 2.0 (RC3)** 的 AI 智能助手服务，提供通用聊天、多模型切换、智能路由、RAG 知识检索、数据分析（NL→SQL→Chart）、会话管理、用户认证、可观测性追踪和 Metabase 数据看板等能力。

项目基于 wu-ai-agent 项目改造，由 **SpringAI 2.x** 架构升级至 **AgentScope Java 2.0** 框架，获得更完整的 ReActAgent 能力和工程化 HarnessAgent 支持。

## 技术栈

| 技术 | 版本 | 说明 |
|---|---|---|
| **Spring Boot** | 4.0.3 | Web 框架 |
| **AgentScope Java** | 2.0.0-RC3 | AI Agent 框架（ReAct 推理-行动循环引擎） |
| **agentscope-harness** | 2.0.0-RC3 | 工程级 Agent 运行时（子Agent编排、Plan模式） |
| **agentscope-extensions-mysql** | 2.0.0-RC3 | MysqlAgentStateStore 会话状态存储 |
| **agentscope-extensions-rag-simple** | 2.0.0-RC3 | RAG：OllamaTextEmbedding、PgVectorStore、SimpleKnowledge |
| **Ollama** | - | 本地大模型服务（qwen3.5:9b、qwen3:14b、deepseek-v2 等） |
| **MySQL** | 8.x | 数据持久化（用户、会话、Agent 状态、调用日志） |
| **PostgreSQL + PgVector** | - | RAG 向量存储 |
| **MyBatis-Plus** | 3.5.16 | ORM 框架 |
| **OpenTelemetry** | 1.61.0 | 分布式追踪（OTLP gRPC → Jaeger） |
| **Metabase** | latest | BI 数据看板（Signed Embedding 嵌入） |
| **SearXNG** | - | 自建元搜索引擎（Bing/百度/Yahoo/Yandex） |
| **Jina Reader** | - | 网页内容提取（URL → Markdown） |
| **Knife4j** | 4.4.0 | API 文档（Swagger） |
| **Hutool** | 5.8.37 | Java 工具库（BCrypt、HTTP、JSON） |
| **JJWT** | 0.12.6 | JWT 签发（Metabase 嵌入认证） |
| **Java** | 21 | JDK 版本 |

## 快速启动

### 环境要求

- JDK 21
- MySQL 8.x（数据库 `agent_scope`）
- PostgreSQL（localhost:5432，用于 PgVectorStore RAG 向量存储）
- Ollama（已拉取模型：`qwen3.5:9b`、`qwen3:14b`、`qwen3:1.7b`、`mxbai-embed-large` 等）
- （可选）Docker（用于 Jaeger、Prometheus、Metabase、SearXNG）

### 启动步骤

```bash
# 1. 创建数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS agent_scope;"

# 2. 创建用户表
mysql -u root -p agent_scope < sql/ai_user.sql

# 3. 创建会话表
mysql -u root -p agent_scope < sql/ai_chat_conversation.sql

# 4. 创建调用日志表
mysql -u root -p agent_scope < sql/agent_call_log.sql

# 5. 拉取 Ollama 模型
ollama pull qwen3.5:9b
ollama pull qwen3:14b
ollama pull qwen3:1.7b
ollama pull mxbai-embed-large

# 6. 启动项目
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

> **注意：** `agent_state` 表由 `MysqlAgentStateStore` 自动创建，PgVector 知识库表由 `SimpleKnowledge` 自动创建，无需手动建表。

启动后访问 API 文档：http://localhost:8133/api/doc.html

### 基础设施（可选）

使用 Docker Compose 启动 Jaeger、Prometheus、SearXNG、Metabase：

```bash
cd D:\cloudflare
docker compose up -d
```

| 服务 | 端口 | 说明 |
|------|------|------|
| PostgreSQL | 5432 | PgVectorStore RAG 向量存储 |
| Jaeger | 16686 | 链路追踪 UI |
| Prometheus | 9090 | 指标监控 |
| SearXNG | 8010 | 联网搜索（JSON API） |
| Metabase | 3000 | 数据看板 |
| Cloudflare Tunnel | - | 公网访问（`*.xpeak.top`） |

## 目录结构

```
wu-agent-scope/
├── pom.xml
├── README.md
├── CLAUDE.md
├── docs/
│   ├── Metabase数据看板接入教程.md              # Metabase 接入教程
│   └── superpowers/                            # 设计文档
├── skills/                                     # Skills 提示词注入
│   ├── article-illustrator/                    # 文章插图生成
│   ├── baoyu-xhs-images/                      # 小红书图文卡片生成
│   ├── content-creation-publisher/             # 内容创作发布全流程
│   ├── data-analyst/                           # 数据分析工作流引导
│   ├── intelligent-content-system/             # 智能内容创作系统
│   └── wechat-hotspot-publisher/               # 微信热点发布（占位）
├── sql/
│   ├── ai_user.sql                             # 用户表 DDL
│   ├── ai_chat_conversation.sql                # 会话表 DDL
│   └── agent_call_log.sql                      # 调用日志表 DDL
└── src/main/
    ├── java/com/chiho/wuagentscope/
    │   ├── WuAgentScopeApplication.java        # 启动类（@MapperScan @EnableAsync）
    │   │
    │   ├── common/                             # 公共模块
    │   │   ├── R.java                          # 统一返回值封装
    │   │   └── exception/
    │   │       ├── ErrorCode.java              # 错误码枚举
    │   │       ├── BusinessException.java      # 业务异常
    │   │       └── GlobalExceptionHandler.java # 全局异常处理
    │   │
    │   ├── config/                             # 配置层
    │   │   ├── AgentScopeConfig.java           # AgentScope 核心配置（Toolkit、Skills、carAdvisorToolkit）
    │   │   ├── ModelAgentRegistry.java         # 多模型 Agent 注册
    │   │   ├── SpecialistAgentRegistry.java    # 专家 Agent 注册（data_analyst、car_advisor）
    │   │   ├── AgentRouterService.java         # 智能路由（意图分类 + L1/L2 上下文持久化）
    │   │   ├── RagConfig.java                  # RAG 配置（PgVector、Embedding、GenericRAGHook）
    │   │   ├── HarnessAgentConfig.java         # HarnessAgent 配置（@Profile("harness")）
    │   │   ├── CorsConfig.java                 # 跨域配置
    │   │   └── OtelConfig.java                 # OpenTelemetry 配置
    │   │
    │   ├── controller/                         # 控制器层
    │   │   ├── AiController.java               # AI 对话接口（SSE 流式 + 同步 + 多模态 POST）
    │   │   ├── ChatController.java             # 会话管理（列表、改名、消息历史、删除）
    │   │   ├── UserController.java             # 用户管理（注册、登录、登出）
    │   │   ├── HealthController.java           # 健康检查
    │   │   ├── DashboardController.java        # Metabase 看板嵌入接口
    │   │   └── HarnessAiController.java        # HarnessAgent 接口（Plan 模式控制）
    │   │
    │   ├── entity/                             # 实体层（MyBatis-Plus）
    │   │   ├── UserDO.java                     # 用户实体（ai_user 表）
    │   │   ├── ChatConversationDO.java         # 会话实体（ai_chat_conversation 表）
    │   │   └── AgentCallLogDO.java             # 调用日志实体（agent_call_log 表）
    │   │
    │   ├── mapper/                             # Mapper 层（MyBatis-Plus）
    │   │   ├── UserMapper.java
    │   │   ├── ChatConversationMapper.java
    │   │   └── AgentCallLogMapper.java
    │   │
    │   ├── middleware/                         # AgentScope 中间件
    │   │   ├── ContextTrimMiddleware.java      # 上下文截断（保留最近 20 轮，图片 2 轮）
    │   │   └── ToolResultTrimMiddleware.java   # 工具结果截断（>2000 字符时截断）
    │   │
    │   ├── model/                              # DTO / VO
    │   │   ├── BaseRequest.java                # POST 请求基类（含 token）
    │   │   ├── ChatRequest.java                # 多模态聊天请求（文本 + 图片）
    │   │   ├── ConversationVO.java             # 会话视图对象
    │   │   ├── MessageVO.java                  # 消息视图对象（含 chartData、imageUrls）
    │   │   ├── RouteResult.java                # 路由结果
    │   │   ├── LoginRequest.java
    │   │   ├── LoginResponse.java
    │   │   └── ImageData.java
    │   │
    │   ├── service/                            # 业务层
    │   │   ├── ChatService.java                # 聊天业务（封装 ReActAgent 调用 + 路由）
    │   │   ├── ChatConversationService.java    # 会话管理（MySQL + AgentStateStore + 图表提取）
    │   │   ├── AgentRouterService.java         # 智能路由服务（qwen3:1.7b 意图分类）
    │   │   ├── DocumentLoaderService.java      # RAG 文档加载（启动时加载 Markdown 知识库）
    │   │   ├── HarnessChatService.java         # HarnessAgent 聊天服务（Plan 模式）
    │   │   ├── UserService.java                # 用户认证（MySQL + BCrypt + Token）
    │   │   ├── MetabaseEmbedService.java       # Metabase JWT 签发
    │   │   └── ObservabilityEventSink.java     # 可观测性事件消费（异步写入日志）
    │   │
    │   └── tools/                              # AgentScope 工具（@Tool 注解）
    │       ├── TimeTool.java                   # 当前时间工具
    │       ├── ImageSearchTool.java            # Pexels 图片搜索工具
    │       ├── WebSearchTool.java              # SearXNG 联网搜索
    │       ├── WebReaderTool.java              # Jina Reader 网页内容提取
    │       ├── TokenUsageTool.java             # Token 用量查询
    │       ├── SchemaInspectorTool.java        # 数据库表结构查询
    │       ├── SqlExecuteTool.java             # 只读 SQL 执行
    │       └── ChartSuggestTool.java           # ECharts 图表配置生成
    │
    └── resources/
        ├── application.yml                     # 主配置
        ├── application-local.yml               # 本地开发配置（MySQL、PostgreSQL、API Keys）
        ├── document/                           # RAG 知识库文档（20 篇购车指南 Markdown）
        └── skills/                             # Skills 提示词目录
```

## 核心架构

### Agent 调用链路

```
用户请求 → AiController → ChatService → AgentRouterService.route(message, sessionId)
                                            │
                                            ├─ qwen3:1.7b 意图分类（thinking 关闭）
                                            ├─ L1 ConcurrentHashMap + L2 MySQL 上下文持久化
                                            └─ RouteResult { general | data_analyst | car_advisor }
                                                    │
                        ┌───────────────────────────┼───────────────────────────┐
                        ▼                           ▼                           ▼
                   通用 Agent                数据分析 Agent             购车顾问 Agent
              (modelId 选择模型)          (专用系统提示词)         (CAR_ADVISOR_PROMPT)
                                                │                           │
                                    SchemaInspectorTool            GenericRAGHook
                                    SqlExecuteTool                 (PgVectorStore 知识检索)
                                    ChartSuggestTool               carAdvisorToolkit（仅 TimeTool）
                                                │                           │
                        └───────────────────────────┴───────────────────────────┘
                                                    │
    ReActAgent.call(List<Msg>, RuntimeContext)
      ├─ 1. ContextTrimMiddleware：截断历史至最近 20 轮，图片保留 2 轮
      ├─ 2. ToolResultTrimMiddleware：工具结果 >2000 字符时截断
      ├─ 3. OtelTracingMiddleware：记录 Span 到 Jaeger
      ├─ 4. DynamicSkillMiddleware：注入 Skill 内容到系统提示词
      ├─ 5. OllamaChatModel 推理（支持多模型切换）
      ├─ 6. 流式返回 TEXT_BLOCK_DELTA 事件 → SSE 推送
      ├─ 7. call() 结束 → 自动保存 AgentState 到 MySQL
      └─ 8. ObservabilityEventSink：异步写入 agent_call_log
```

### 多模型架构

模型在 `application.yml` 的 `agentscope.models.available` 中配置，每个模型启动时自动创建独立的 `ReActAgent` 实例。所有 Agent 共享同一个 `AgentStateStore`（会话历史与模型无关）和 `Toolkit`（相同工具集）。

- **消息级绑定**：`modelId` 是请求级参数，用户可在同一会话中自由切换模型
- **lastModelId**：`ai_chat_conversation` 存储最后使用的模型，作为 UI 提示（非约束）
- **添加新模型**：在 YAML 中添加配置项，无需改代码

### 智能路由

系统使用 qwen3:1.7b 进行轻量级意图分类，将用户消息路由到合适的专家 Agent：

- **通用聊天** (`general`) — 使用用户选择的模型，完整工具集
- **数据分析** (`data_analyst`) — 专用系统提示词，引导 SQL 查询工作流：`inspect_database_schema` → `execute_sql_query` → `suggest_chart_config` → 总结结果
- **购车顾问** (`car_advisor`) — 专用 `CAR_ADVISOR_PROMPT`，`GenericRAGHook` 自动检索 PgVectorStore 中的购车知识库，`carAdvisorToolkit` 仅含 `TimeTool`

路由上下文持久化：L1 `ConcurrentHashMap` 快速查找 + L2 MySQL `last_route`/`last_route_msg` 跨会话连续性。

### RAG 知识检索

基于 AgentScope `agentscope-extensions-rag-simple` 模块实现的 RAG 子系统：(简单实现，后续2.0正式版切换核心包相关API)

```
DocumentLoaderService（启动时）→ TextReader(chunk=400, PARAGRAPH) → SHA-256 去重 → PgVectorStore
GenericRAGHook（每次请求）→ SimpleKnowledge.search() → OllamaTextEmbedding(mxbai-embed-large, 1024d) → 注入提示词
```

- **Embedding 模型**：`mxbai-embed-large`（1024 维，通过 Ollama 本地推理）
- **向量存储**：PostgreSQL + PgVector 扩展
- **知识库**：`src/main/resources/document/` 下 20 篇购车指南 Markdown 文档

### 数据分析工具

三个工具实现自然语言数据分析：

| 工具 | 说明 |
|------|------|
| `inspect_database_schema` | 查询 `information_schema` 获取表结构（仅暴露 `agent_scope` 库） |
| `execute_sql_query` | 只读 SELECT 查询（白名单、关键词黑名单、自动 LIMIT 1000、30s 超时） |
| `suggest_chart_config` | 分析查询结果生成 ECharts 配置（饼图、柱状图、折线图、分组柱状图） |

**图表渲染流程：**
```
Agent 调用 suggest_chart_config → 返回 {"chartType", "title", "echartsOption"}
  → Agent 在回复文本中包含 JSON（可能包裹在 markdown 代码块中）
  → ChatConversationService.extractChartData() 提取 JSON
  → 存入 MessageVO.chartData
  → 前端 EChart.vue 组件使用 echarts 渲染
```

### 多模态（视觉）支持

支持 `capabilities: [text, vision]` 的模型可接收图片输入：

- **GET** `/ai/chat/common/sse` — 纯文本，前端使用 `EventSource`（向后兼容）
- **POST** `/ai/chat/common/sse` — 文本 + 图片，前端使用 `fetch()` + `ReadableStream`

`ContextTrimMiddleware` 自动优化：仅保留最近 2 轮图片，更早的消息替换为 `[图片已省略]` 占位符。

### 可观测性

两层追踪机制：

| 层级 | 组件 | 作用 |
|------|------|------|
| 框架层 | `OtelTracingMiddleware` | AgentScope 中间件链内记录 Span，导出到 Jaeger |
| 事件层 | `ObservabilityEventSink` | 消费 `streamEvents()` 事件流，异步写入 `agent_call_log` 表（含 `model_name`） |

### Metabase 数据看板

通过 Signed Embedding（JWT）将 Metabase Dashboard 嵌入应用：

```
前端 → GET /api/dashboard/embed?token=xxx → 后端签发 JWT → 返回嵌入 URL → iframe 展示
```

详细接入教程见 [docs/Metabase数据看板接入教程.md](docs/Metabase数据看板接入教程.md)。

## REST 接口

### AI 对话（/api/ai）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ai/chat/common/sse` | 通用聊天（SSE 流式输出，纯文本） |
| POST | `/ai/chat/common/sse` | 多模态聊天（SSE 流式输出，文本 + 图片） |
| GET | `/ai/chat/common` | 通用聊天（同步返回） |
| GET | `/ai/models` | 获取可用模型列表 |

### 会话管理（/api/chat）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/chat/conversations` | 获取会话列表 |
| PUT | `/chat/conversation/name` | 修改会话名称 |
| GET | `/chat/conversation/messages` | 查询对话历史消息 |
| DELETE | `/chat/conversation` | 删除会话（级联删除调用日志和 Agent 状态） |

### 用户管理（/api/user）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/register` | 用户注册 |
| POST | `/user/login` | 用户登录 |
| POST | `/user/logout` | 用户登出 |
| GET | `/user/validate` | 验证 Token |

### 数据看板（/api/dashboard）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/dashboard/embed` | 获取 Metabase 嵌入 URL（JWT 签名） |

### HarnessAgent（/api/harness/ai，需 `harness` Profile）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/harness/ai/chat/sse` | HarnessAgent 聊天（SSE 流式） |
| POST | `/harness/ai/plan/enter` | 进入 Plan 模式 |
| POST | `/harness/ai/plan/exit` | 退出 Plan 模式 |
| GET | `/harness/ai/plan/status` | 查询 Plan 模式状态 |
| GET | `/harness/ai/info` | 获取 Agent 信息 |

### 健康检查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 服务健康状态 |

## MySQL 表结构

```sql
-- 用户表
CREATE TABLE IF NOT EXISTS `ai_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL COMMENT 'BCrypt 加密',
  `nickname` varchar(64) DEFAULT NULL,
  `email` varchar(128) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-正常 0-禁用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话表
CREATE TABLE IF NOT EXISTS `ai_chat_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `conversation_id` varchar(128) NOT NULL,
  `conversation_name` varchar(128) DEFAULT NULL,
  `last_model_id` varchar(64) DEFAULT NULL COMMENT '最后使用的模型 ID',
  `last_route` varchar(32) DEFAULT NULL COMMENT '最后路由类型（general/data_analyst/car_advisor）',
  `last_route_msg` text DEFAULT NULL COMMENT '最后路由决策原因',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent 调用日志表
CREATE TABLE IF NOT EXISTS `agent_call_log` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` VARCHAR(64)  NOT NULL     COMMENT '会话ID（chatId）',
    `user_id`         BIGINT       NOT NULL     COMMENT '用户ID',
    `run_id`          VARCHAR(64)  NOT NULL     COMMENT 'AgentScope run_id',
    `event_type`      VARCHAR(32)  NOT NULL     COMMENT '事件类型：MODEL_CALL_END / TOOL_RESULT_END / AGENT_END',
    `input_tokens`    INT                      COMMENT '输入 token 数',
    `output_tokens`   INT                      COMMENT '输出 token 数',
    `model_name`      VARCHAR(64)              COMMENT '模型名称',
    `tool_name`       VARCHAR(64)              COMMENT '工具名称',
    `tool_state`      VARCHAR(16)              COMMENT '工具调用结果状态：SUCCESS / ERROR / DENIED / INTERRUPTED',
    `duration_ms`     BIGINT                   COMMENT '耗时（毫秒）',
    `detail`          JSON                     COMMENT '完整事件详情 JSON',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_conversation` (`conversation_id`),
    INDEX `idx_user_time` (`user_id`, `created_at`),
    INDEX `idx_run_id` (`run_id`),
    INDEX `idx_event_type` (`event_type`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 调用日志';

-- Agent 状态表（MysqlAgentStateStore 自动建表，无需手动创建）
-- PgVector 知识库表（SimpleKnowledge 自动建表，无需手动创建）
```
