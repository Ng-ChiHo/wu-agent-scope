# Metabase 数据看板接入教程

本教程记录如何将 Metabase 嵌入到 Spring Boot + Vue 3 项目中，实现 AI Agent 调用数据的可视化看板。

## 架构概览

```
┌──────────────┐     GET /dashboard/embed      ┌──────────────────┐
│   Vue 前端    │ ──────────────────────────→  │  Spring Boot 后端  │
│  (iframe)    │                                │  签发 JWT token   │
└──────┬───────┘                                └──────────────────┘
       │ iframe                                         │
       │ src=xxx.xpeak.top/embed/dashboard/JWT          │ JWT 验证
       ▼                                                ▼
┌──────────────────────────────────────────────────────────┐
│                     Metabase                             │
│  连接 MySQL agent_scope 库，渲染 Dashboard 图表           │
└──────────────────────────────────────────────────────────┘
```

**核心原理：** 后端用 Embedding Secret Key 签发 JWT（含 Dashboard ID + 用户参数），前端将 JWT URL 放入 iframe，Metabase 验证签名后展示过滤后的数据。

---

## 1. Docker 部署 Metabase

在 `docker-compose.yml` 中添加 Metabase 服务：

```yaml
metabase:
  image: metabase/metabase:latest
  container_name: metabase
  ports:
    - "3000:3000"
  environment:
    MB_EMBEDDING_SECRET_KEY: ${METABASE_EMBEDDING_SECRET_KEY}
    MB_ENABLE_EMBEDDING: "true"
    MB_DB_TYPE: h2
  volumes:
    - ./data/metabase:/metabase-data
  extra_hosts:
    - "host.docker.internal:host-gateway"  # 访问宿主机 MySQL
  restart: unless-stopped
```

在 `.env` 中生成密钥：

```bash
# 生成 32 字节密钥
openssl rand -base64 32
# 输出示例: U5K4ZFGf1WOBN68DDwV1dK32N2yH0G1HGsGPwtxxxxx=
```

```env
METABASE_EMBEDDING_SECRET_KEY=U5K4ZFGf1WOBN68DDwV1dK32N2yH0G1HGsGPwxxxxx=
```

启动：

```bash
docker compose up -d metabase
```

---

## 2. Metabase 初始化配置

### 2.1 初始化向导

访问 `http://localhost:3000`，按向导完成账号创建。

### 2.2 添加数据库连接

Admin Settings → Databases → Add a database → MySQL：

| 字段 | 值                      |
|------|------------------------|
| Host | `host.docker.internal` |
| Port | `3306`                 |
| Database name | `agent_scope`          |
| Username | `user`                 |
| Password | `123pwd...`            |

### 2.3 开启 Embedding

Admin Settings → Embedding：

- **Enable embedding:** ON
- **Enable guest embeds:** ON（即 Signed Embedding / JWT）
- **Enable public sharing:** OFF
- **Embedding secret key:** 粘贴 `.env` 中的密钥（Docker 环境变量会自动注入，确认一致即可）

### 2.4 创建 Dashboard

在 Metabase 中创建 SQL Question 并组装 Dashboard，示例查询：

```sql
-- Token 用量趋势（折线图）
SELECT DATE(created_at) AS `日期`,
       SUM(input_tokens) AS `输入 Token`,
       SUM(output_tokens) AS `输出 Token`
FROM agent_call_log
WHERE event_type = 'MODEL_CALL_END'
  AND created_at >= DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY)
GROUP BY DATE(created_at)
ORDER BY `日期`

-- 工具调用成功率（饼图）
SELECT tool_state AS `状态`, COUNT(*) AS `次数`
FROM agent_call_log
WHERE event_type = 'TOOL_RESULT_END'
GROUP BY tool_state

-- 会话耗时分布（柱状图）
SELECT FLOOR(duration_ms / 1000) AS `耗时(秒)`, COUNT(*) AS `次数`
FROM agent_call_log
WHERE event_type = 'AGENT_END'
GROUP BY FLOOR(duration_ms / 1000)
ORDER BY `耗时(秒)`
```

组装 Dashboard 后，点击右上角分享图标 → **Enable embedding**，记录 URL 中的 Dashboard ID：

```
http://localhost:3000/dashboard/3-agent-scope
                                ↑
                           Dashboard ID = 3
```

---

## 3. Spring Boot 后端集成

### 3.1 添加 JWT 依赖

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

### 3.2 配置 application.yml

```yaml
metabase:
  site-url: https://xxx.xpeak.top
  embedding-secret-key: ${METABASE_EMBEDDING_SECRET_KEY:U5K4ZFGf1WOBN68DDwV1dK32N2yH0G1HGsGPwxxxxx=}
  dashboard-id: 3
```

### 3.3 MetabaseEmbedService

签发 JWT 并生成嵌入 URL：

```java
@Service
@Slf4j
public class MetabaseEmbedService {

    @Value("${metabase.site-url}")
    private String metabaseSiteUrl;

    @Value("${metabase.embedding-secret-key}")
    private String embeddingSecretKey;

    @Value("${metabase.dashboard-id}")
    private Integer dashboardId;

    private static final long TOKEN_EXPIRY_MS = 24 * 60 * 60 * 1000L; // 24小时

    public String generateEmbedUrl(Long userId) {
        // 构建 JWT payload
        Map<String, Object> resource = Map.of("dashboard", dashboardId);
        Map<String, Object> params = Map.of("user_id", String.valueOf(userId));

        Map<String, Object> payload = new HashMap<>();
        payload.put("resource", resource);
        payload.put("params", params);
        payload.put("exp", new Date(System.currentTimeMillis() + TOKEN_EXPIRY_MS));

        // HS256 签名
        SecretKey key = new SecretKeySpec(
                embeddingSecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        String token = Jwts.builder().claims(payload).signWith(key).compact();

        return metabaseSiteUrl + "/embed/dashboard/" + token
                + "#bordered=false&titled=false&theme=transparent";
    }
}
```

### 3.4 DashboardController

提供嵌入 URL 接口：

```java
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Resource
    private MetabaseEmbedService metabaseEmbedService;

    @Resource
    private UserService userService;

    @GetMapping("/embed")
    public R<String> getEmbedUrl(@RequestParam String token) {
        Long userId = userService.getUserIdByToken(token);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_TOKEN);
        }
        return R.success(metabaseEmbedService.generateEmbedUrl(userId));
    }
}
```

**接口：** `GET /api/dashboard/embed?token=xxx` → 返回 Metabase 嵌入 URL。

---

## 4. Vue 3 前端嵌入

### 4.1 侧边栏入口

在 `ChatRoom.vue` 的 sidebar-header 中添加入口链接：

```html
<a href="#" @click.prevent="openDashboard" class="jaeger-link">
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" width="14" height="14">
    <rect x="3" y="12" width="4" height="9" rx="1" />
    <rect x="10" y="7" width="4" height="14" rx="1" />
    <rect x="17" y="3" width="4" height="18" rx="1" />
  </svg>
  Metabase 数据看板
</a>
```

### 4.2 状态与方法

```js
const showDashboard = ref(false)
const dashboardUrl = ref('')

async function openDashboard() {
  if (dashboardUrl.value) {
    showDashboard.value = true
    return
  }
  try {
    const res = await fetchWithToken('/dashboard/embed')
    if (res.code === 0) {
      dashboardUrl.value = res.data
      showDashboard.value = true
    }
  } catch (e) {
    console.error('获取 Dashboard URL 失败:', e)
  }
}
```

### 4.3 iframe 容器

在主内容区用 `v-if/v-else` 切换聊天界面和 Dashboard：

```html
<main class="chat-area">
  <!-- Dashboard -->
  <div v-if="showDashboard" class="dashboard-container">
    <div class="dashboard-header">
      <button class="back-btn" @click="showDashboard = false">
        ← 返回对话
      </button>
      <span class="dashboard-title">Metabase 数据看板</span>
    </div>
    <iframe :src="dashboardUrl" frameborder="0" class="dashboard-iframe"></iframe>
  </div>

  <!-- 聊天界面 -->
  <template v-else>
    <!-- 原有聊天内容 -->
  </template>
</main>
```

### 4.4 样式

```css
.dashboard-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
}

.dashboard-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(57, 255, 20, 0.08);
  background: rgba(0, 0, 0, 0.3);
}

.back-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  background: rgba(57, 255, 20, 0.06);
  border: 1px solid rgba(57, 255, 20, 0.15);
  border-radius: 2px;
  color: rgba(57, 255, 20, 0.7);
  font-size: 0.8rem;
  font-family: 'Courier New', monospace;
  cursor: pointer;
  transition: all 0.3s;
}

.back-btn:hover {
  background: rgba(57, 255, 20, 0.12);
  color: #39ff14;
}

.dashboard-title {
  color: rgba(57, 255, 20, 0.6);
  font-size: 0.85rem;
  font-family: 'Courier New', monospace;
}

.dashboard-iframe {
  flex: 1;
  width: 100%;
  border: none;
  background: #ffffff;
}
```

---

## 5. 域名绑定（可选）

通过 Cloudflare Tunnel 将 Metabase 暴露到公网：

1. Cloudflare Zero Trust → Networks → Tunnels → Add a public hostname
2. 配置：

| 字段 | 值                                  |
|------|------------------------------------|
| Subdomain | `xxx`                              |
| Domain | `xpeak.top`                        |
| Service Type | `HTTPS`                            |
| Service URL | `http://host.docker.internal:3000` |

3. 更新 `application.yml` 中的 `metabase.site-url` 为 `https://xxx.xpeak.top`

---

## 6. 文件清单

| 文件 | 项目 | 说明 |
|------|------|------|
| `docker-compose.yml` | infra | 添加 metabase 服务 |
| `.env` | infra | METABASE_EMBEDDING_SECRET_KEY |
| `pom.xml` | 后端 | 添加 jjwt 依赖 |
| `application.yml` | 后端 | metabase 配置段 |
| `MetabaseEmbedService.java` | 后端 | JWT 签发 + URL 生成 |
| `DashboardController.java` | 后端 | GET /dashboard/embed 接口 |
| `ChatRoom.vue` | 前端 | 入口链接 + iframe 嵌入 |

---

## 7. 注意事项

- **JWT 过期时间：** 建议 24 小时，过短影响用户体验，过长有安全风险
- **Embedding Secret Key：** 后端、Docker 环境变量、Metabase 三处必须一致
