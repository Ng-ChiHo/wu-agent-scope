# wu-agent-scope

项目基于 wu-ai-agent 项目改造而成，由 **SpringAI 2.x** 项目架构升级而成。 **AgentScope Java 2.0** 框架提供更完整的 ReActAgent 能力，同时支持工程化能力 HarnessAgent。 

基于 **AgentScope Java 2.0** 的 AI 智能助手服务，提供通用聊天、会话管理、用户认证等能力。

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
| **MySQL** | 8.x | 数据持久化（用户、会话、Agent 状态） |
| **MyBatis-Plus** | 3.5.16 | ORM 框架 |
| **Knife4j** | 4.4.0 | API 文档（Swagger） |
| **Hutool** | 5.8.37 | Java 工具库（BCrypt 加密等） |
| **Lombok** | 1.18.36 | 代码简化 |
| **Java** | 21 | JDK 版本 |

## 快速启动

### 环境要求

- JDK 21
- MySQL 8.x（数据库 `agent_scope`）
- Ollama（已拉取 `qwen3:14b` 模型）

### 启动步骤

```bash
# 1. 创建数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS agent_scope;"

# 2. 创建用户表
mysql -u root -p agent_scope < sql/ai_user.sql

# 3. 创建会话表
mysql -u root -p agent_scope < sql/ai_chat_conversation.sql

# 4. 拉取 Ollama 模型
ollama pull qwen3:14b

# 5. 启动项目
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

启动后访问 API 文档：http://localhost:8133/api/doc.html

## 目录结构

```
wu-agent-scope/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/chiho/wuagentscope/
    │   ├── WuAgentScopeApplication.java             # 启动类（@MapperScan）
    │   │
    │   ├── common/                                   # 公共模块
    │   │   ├── R.java                                # 统一返回值封装
    │   │   └── exception/
    │   │       ├── ErrorCode.java                    # 错误码枚举
    │   │       ├── BusinessException.java            # 业务异常
    │   │       └── GlobalExceptionHandler.java       # 全局异常处理
    │   │
    │   ├── config/                                   # 配置层
    │   │   └── AgentScopeConfig.java                 # AgentScope 核心配置
    │   │                                             # （ReActAgent + OllamaChatModel + MysqlAgentStateStore）
    │   │
    │   ├── controller/                               # 控制器层
    │   │   ├── AiController.java                     # AI 对话接口（SSE 流式 + 同步）
    │   │   ├── ChatController.java                   # 会话管理（列表、改名、消息历史、删除）
    │   │   ├── UserController.java                   # 用户管理（注册、登录、登出）
    │   │   └── HealthController.java                 # 健康检查
    │   │
    │   ├── entity/                                   # 实体层（MyBatis-Plus）
    │   │   ├── UserDO.java                           # 用户实体（ai_user 表）
    │   │   └── ChatConversationDO.java               # 会话实体（ai_chat_conversation 表）
    │   │
    │   ├── mapper/                                   # Mapper 层（MyBatis-Plus）
    │   │   ├── UserMapper.java                       # 用户 Mapper
    │   │   └── ChatConversationMapper.java           # 会话 Mapper
    │   │
    │   ├── model/                                    # DTO / VO
    │   │   ├── LoginRequest.java                     # 登录请求
    │   │   ├── LoginResponse.java                    # 登录响应
    │   │   ├── ConversationVO.java                   # 会话信息 VO
    │   │   └── MessageVO.java                        # 对话消息 VO（role + content + timestamp）
    │   │
    │   └── service/                                  # 业务层
    │       ├── ChatService.java                      # 聊天业务（封装 ReActAgent 调用）
    │       ├── ChatConversationService.java          # 会话管理（MySQL + AgentStateStore）
    │       └── UserService.java                      # 用户认证（MySQL + BCrypt + Token）
    │
    └── resources/
        ├── application.yml                           # 主配置
        └── application-local.yml                     # 本地开发配置（MySQL 连接）
```

## 核心架构

### Agent 调用链路

```
用户请求 → AiController → ChatService → ReActAgent
                                            │
                    ┌───────────────────────┘
                    │
    ReActAgent.call(List<Msg>, RuntimeContext)
      ├─ 1. 从 MysqlAgentStateStore 加载历史对话状态
      ├─ 2. OllamaChatModel（qwen3:14b）推理
      ├─ 3. 流式返回 TEXT_BLOCK_DELTA 事件 → SSE 推送
      └─ 4. call() 结束 → 自动保存 AgentState 到 MySQL
```

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
| DELETE | `/chat/conversation` | 删除会话 |

### 用户管理（/api/user）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/register` | 用户注册 |
| POST | `/user/login` | 用户登录 |
| POST | `/user/logout` | 用户登出 |
| GET | `/user/validate` | 验证 Token |

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

-- Agent 状态表（MysqlAgentStateStore 自动建表，无需手动创建）
```
