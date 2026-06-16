# wu-agent-scope

项目基于 wu-ai-agent 项目改造而成，由 **SpringAI 2.x** 项目架构升级而成。 **AgentScope Java 2.0** 框架提供更完整的 ReActAgent 能力，同时支持工程化能力 HarnessAgent。 

基于 **AgentScope Java 2.0** 的 AI 智能助手服务，提供通用聊天、会话管理、用户认证、可观测性追踪和 Metabase 数据看板等能力。

## 规划接入：
- Tool Use工具能力接入；MCP服务接
- RAG知识库检索
- 长期记忆功能
- Skills接入

## 技术栈

| 技术 | 版本 | 说明 |
|---|---|---|
| **Spring Boot** | 4.0.3 | Web 框架 |
| **AgentScope Java** | 2.0.0-RC3 | AI Agent 框架（ReAct 推理-行动循环引擎） |
| **Ollama** | - | 本地大模型服务（默认 qwen3:14b） |
| **MySQL** | 8.x | 数据持久化（用户、会话、Agent 状态、调用日志） |
| **MyBatis-Plus** | 3.5.16 | ORM 框架 |
| **OpenTelemetry** | 1.61.0 | 分布式追踪（OTLP gRPC → Jaeger） |
| **Metabase** | latest | BI 数据看板（Signed Embedding 嵌入） |
| **Knife4j** | 4.4.0 | API 文档（Swagger） |
| **Hutool** | 5.8.37 | Java 工具库（BCrypt 加密等） |
| **JJWT** | 0.12.6 | JWT 签发（Metabase 嵌入认证） |
| **Lombok** | 1.18.36 | 代码简化 |
| **Java** | 21 | JDK 版本 |

## 快速启动

### 环境要求

- JDK 21
- MySQL 8.x（数据库 `agent_scope`）
- Ollama（已拉取 `qwen3:14b` 模型）
- （可选）Docker（用于 Jaeger、Prometheus、Metabase）

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
ollama pull qwen3:14b

# 6. 启动项目
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

> **注意：** `agent_state` 表由 `MysqlAgentStateStore` 自动创建，无需手动建表。

启动后访问 API 文档：http://localhost:8133/api/doc.html

### 基础设施（可选）

使用 Docker Compose 启动 Jaeger、Prometheus、Metabase：

```bash
cd D:\cloudflare
docker compose up -d
```

| 服务 | 端口 | 说明 |
|------|------|------|
| Jaeger | 16686 | 链路追踪 UI |
| Prometheus | 9090 | 指标监控 |
| Metabase | 3000 | 数据看板 |

## 目录结构

```
wu-agent-scope/
├── pom.xml
├── README.md
├── CLAUDE.md
├── docs/
│   └── metabase-integration-guide.md          # Metabase 接入教程
├── sql/
│   └── agent_call_log.sql                     # 调用日志表 DDL
└── src/main/
    ├── java/com/chiho/wuagentscope/
    │   ├── WuAgentScopeApplication.java       # 启动类（@MapperScan @EnableAsync）
    │   │
    │   ├── common/                            # 公共模块
    │   │   ├── R.java                         # 统一返回值封装
    │   │   └── exception/
    │   │       ├── ErrorCode.java             # 错误码枚举
    │   │       ├── BusinessException.java     # 业务异常
    │   │       └── GlobalExceptionHandler.java # 全局异常处理
    │   │
    │   ├── config/                            # 配置层
    │   │   ├── AgentScopeConfig.java          # AgentScope 核心配置
    │   │   ├── CorsConfig.java                # 跨域配置
    │   │   └── OtelConfig.java                # OpenTelemetry 配置
    │   │
    │   ├── controller/                        # 控制器层
    │   │   ├── AiController.java              # AI 对话接口（SSE 流式 + 同步）
    │   │   ├── ChatController.java            # 会话管理（列表、改名、消息历史、删除）
    │   │   ├── UserController.java            # 用户管理（注册、登录、登出）
    │   │   ├── HealthController.java          # 健康检查
    │   │   └── DashboardController.java       # Metabase 看板嵌入接口
    │   │
    │   ├── entity/                            # 实体层（MyBatis-Plus）
    │   │   ├── UserDO.java                    # 用户实体（ai_user 表）
    │   │   ├── ChatConversationDO.java        # 会话实体（ai_chat_conversation 表）
    │   │   └── AgentCallLogDO.java            # 调用日志实体（agent_call_log 表）
    │   │
    │   ├── mapper/                            # Mapper 层（MyBatis-Plus）
    │   │   ├── UserMapper.java
    │   │   ├── ChatConversationMapper.java
    │   │   └── AgentCallLogMapper.java
    │   │
    │   ├── middleware/                        # AgentScope 中间件
    │   │   └── ContextTrimMiddleware.java     # 上下文截断（保留最近 N 轮）
    │   │
    │   ├── model/                             # DTO / VO
    │   │   ├── LoginRequest.java
    │   │   ├── LoginResponse.java
    │   │   ├── ConversationVO.java
    │   │   └── MessageVO.java
    │   │
    │   ├── service/                           # 业务层
    │   │   ├── ChatService.java               # 聊天业务（封装 ReActAgent 调用）
    │   │   ├── ChatConversationService.java   # 会话管理（MySQL + AgentStateStore）
    │   │   ├── UserService.java               # 用户认证（MySQL + BCrypt + Token）
    │   │   ├── MetabaseEmbedService.java      # Metabase JWT 签发
    │   │   └── ObservabilityEventSink.java    # 可观测性事件消费（异步写入日志）
    │   │
    │   └── tools/                             # AgentScope 工具
    │       ├── TimeTool.java                  # 当前时间工具
    │       └── ImageSearchTool.java           # Pexels 图片搜索工具
    │
    └── resources/
        ├── application.yml                    # 主配置
        └── application-local.yml              # 本地开发配置（MySQL + Pexels + Metabase）
```

## 核心架构

### Agent 调用链路

```
用户请求 → AiController → ChatService → ReActAgent
                                            │
                    ┌───────────────────────┘
                    │
    ReActAgent.call(List<Msg>, RuntimeContext)
      ├─ 1. ContextTrimMiddleware：截断历史至最近 20 轮
      ├─ 2. OtelTracingMiddleware：记录 Span 到 Jaeger
      ├─ 3. OllamaChatModel（qwen3:14b）推理
      ├─ 4. 流式返回 TEXT_BLOCK_DELTA 事件 → SSE 推送
      ├─ 5. call() 结束 → 自动保存 AgentState 到 MySQL
      └─ 6. ObservabilityEventSink：异步写入 agent_call_log
```

### 可观测性

两层追踪机制：

| 层级 | 组件 | 作用 |
|------|------|------|
| 框架层 | `OtelTracingMiddleware` | AgentScope 中间件链内记录 Span，导出到 Jaeger |
| 事件层 | `ObservabilityEventSink` | 消费 `streamEvents()` 事件流，异步写入 `agent_call_log` 表 |

### Metabase 数据看板

通过 Signed Embedding（JWT）将 Metabase Dashboard 嵌入应用：

```
前端 → GET /api/dashboard/embed?token=xxx → 后端签发 JWT → 返回嵌入 URL → iframe 展示
```

详细接入教程见 [docs/metabase-integration-guide.md](docs/metabase-integration-guide.md)。

### AgentStateStore vs Spring AI ChatMemory

| 维度 | Spring AI ChatMemory | AgentScope AgentStateStore |
|---|---|---|
| **存储内容** | 仅消息历史 | 完整 AgentState（消息 + 摘要 + 权限 + 计划） |
| **注入方式** | Advisor 手动配置 | ReActAgent 在 call() 入口自动加载 |
| **MySQL 实现** | 自定义 UserChatMemory | 内置 MysqlAgentStateStore |
| **并发隔离** | 按 conversationId | 按 (userId, sessionId) 二元组 |

## REST 接口

### AI 对话（/api/ai）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ai/chat/common/sse` | 通用聊天（SSE 流式输出） |
| GET | `/ai/chat/common` | 通用聊天（同步返回） |

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

### 健康检查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 服务健康状态 |

## MySQL 表结构

```sql
-- 用户表
CREATE TABLE `ai_user` (
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

-- 会话关联表
CREATE TABLE `ai_chat_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `conversation_id` varchar(128) NOT NULL,
  `conversation_name` varchar(128) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Agent 调用日志表（详见 sql/agent_call_log.sql）
-- Agent 状态表（MysqlAgentStateStore 自动建表，无需手动创建）
```
