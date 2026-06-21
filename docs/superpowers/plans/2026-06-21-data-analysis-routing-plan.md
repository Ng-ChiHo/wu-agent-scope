# 数据分析 + 智能路由 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 wu-agent-scope 新增数据分析（NL→SQL→Chart）和智能路由（意图分类→专业 Agent）两大能力。

**Architecture:** 共享 Toolkit + Tool Groups 实现工具隔离；Router 用 qwen3-vl:8b 轻量模型做意图分类；每个专业 Agent 是独立 ReActAgent 实例，有专属 system prompt；模型选择与 Agent 路由独立。

**Tech Stack:** Spring Boot 4.0.3, AgentScope 2.0.0-RC3, MyBatis-Plus, Ollama, ECharts 5.x

## Global Constraints

- Java 21，遵循现有 `com.chiho.wuagentscope` 包结构
- Tool 使用 `@Tool` + `@ToolParam` 注解，注册到 `AgentScopeConfig.toolkit()` 和 `HarnessAgentConfig.harnessToolkit()`
- 新增 ErrorCode 枚举值需在 `ErrorCode.java` 中定义
- `@Profile("!harness")` 的 Service 只在非 harness 模式生效
- API 响应统一使用 `R<T>` 包装
- 敏感配置放 `application-local.yml`，非敏感放 `application.yml`

---

## 文件结构

### 新增文件

| 文件 | 职责 |
|------|------|
| `tools/SchemaInspectorTool.java` | 查询数据库表结构 |
| `tools/SqlExecuteTool.java` | 执行只读 SQL 查询 |
| `tools/ChartSuggestTool.java` | 分析数据特征，生成 ECharts 配置 |
| `model/RouteResult.java` | 路由分类结果 |
| `service/AgentRouterService.java` | 意图分类服务 |
| `config/SpecialistAgentRegistry.java` | 专业 Agent 管理 |
| `skills/data-analyst/SKILL.md` | 数据分析 Agent 专属 prompt |

### 修改文件

| 文件 | 变更 |
|------|------|
| `config/AgentScopeConfig.java` | toolkit() 改用 Tool Groups 注册 |
| `config/HarnessAgentConfig.java` | harnessToolkit() 同步改造 |
| `config/ModelAgentRegistry.java` | 暴露 getModel() 方法供 SpecialistAgentRegistry 使用 |
| `model/MessageVO.java` | 新增 chartData 字段 |
| `service/ChatService.java` | 接入 Router，调用 SpecialistAgentRegistry |
| `service/ChatConversationService.java` | toMessageVO() 解析图表数据 |
| `controller/AiController.java` | modelId 参数变为可选 |
| `common/exception/ErrorCode.java` | 新增 SQL_EXECUTE_FORBIDDEN 等错误码 |
| `pom.xml` | 无需变更 |
| `application.yml` | 无需变更 |

---

### Task 1: SchemaInspectorTool — 数据库表结构查询工具

**Files:**
- Create: `src/main/java/com/chiho/wuagentscope/tools/SchemaInspectorTool.java`

**Interfaces:**
- Consumes: `javax.sql.DataSource`（Spring 自动注入）
- Produces: `inspect_database_schema` Tool，供 Agent 调用获取表结构

- [ ] **Step 1: 创建 SchemaInspectorTool.java**

```java
package com.chiho.wuagentscope.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库表结构查询工具
 * <p>
 * 查询 agent_scope 库的表结构信息，让 Agent 了解可用的表和字段。
 * 只暴露当前业务库，不支持跨库查询（安全边界）。
 *
 * @author ChiHo
 */
@Component
@Slf4j
public class SchemaInspectorTool {

    private static final String TARGET_SCHEMA = "agent_scope";

    private final DataSource dataSource;

    public SchemaInspectorTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "inspect_database_schema",
          description = "当用户询问数据、报表、统计相关问题时，先调用此工具获取数据库表结构。" +
                       "不传参数返回所有表概览，传入表名返回该表的字段详情。")
    public String inspectSchema(
            @ToolParam(name = "table_name", description = "表名，可选。不传则返回所有表列表") String tableName) {
        log.info("##### ToolUse[SchemaInspectorTool-inspect_database_schema]: tableName={}", tableName);
        try {
            if (tableName == null || tableName.isBlank()) {
                return listTables();
            }
            return describeTable(tableName.trim());
        } catch (Exception e) {
            log.error("查询表结构失败: tableName={}", tableName, e);
            return "Error: " + e.getMessage();
        }
    }

    private String listTables() throws SQLException {
        JSONArray tables = new JSONArray();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES " +
                     "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            ps.setString(1, TARGET_SCHEMA);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject table = new JSONObject();
                    table.set("name", rs.getString("TABLE_NAME"));
                    table.set("comment", rs.getString("TABLE_COMMENT"));
                    tables.add(table);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.set("schema", TARGET_SCHEMA);
        result.set("tables", tables);
        return result.toString();
    }

    private String describeTable(String tableName) throws SQLException {
        // 校验表名防注入（只允许字母数字下划线）
        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            return "Error: invalid table name";
        }

        // 检查表是否属于目标库
        boolean exists = false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, TARGET_SCHEMA);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) exists = rs.getInt(1) > 0;
            }
        }
        if (!exists) {
            return "Error: table '" + tableName + "' not found in " + TARGET_SCHEMA;
        }

        JSONArray columns = new JSONArray();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT " +
                     "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                     "ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, TARGET_SCHEMA);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject col = new JSONObject();
                    col.set("name", rs.getString("COLUMN_NAME"));
                    col.set("type", rs.getString("COLUMN_TYPE"));
                    col.set("nullable", "YES".equals(rs.getString("IS_NULLABLE")));
                    col.set("key", rs.getString("COLUMN_KEY"));
                    col.set("comment", rs.getString("COLUMN_COMMENT"));
                    columns.add(col);
                }
            }
        }

        JSONObject result = new JSONObject();
        result.set("schema", TARGET_SCHEMA);
        result.set("table", tableName);
        result.set("columns", columns);
        return result.toString();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/tools/SchemaInspectorTool.java
git commit -m "feat(tools): add SchemaInspectorTool for database schema inspection"
```

---

### Task 2: SqlExecuteTool — 只读 SQL 执行工具

**Files:**
- Create: `src/main/java/com/chiho/wuagentscope/tools/SqlExecuteTool.java`
- Modify: `src/main/java/com/chiho/wuagentscope/common/exception/ErrorCode.java`

**Interfaces:**
- Consumes: `javax.sql.DataSource`
- Produces: `execute_sql_query` Tool，供 Agent 执行 SELECT 查询

- [ ] **Step 1: 新增 ErrorCode**

在 `ErrorCode.java` 的 `MODEL_NOT_FOUND(40020, ...)` 后添加：

```java
SQL_EXECUTE_FORBIDDEN(40030, "SQL执行被禁止：仅支持SELECT查询"),
SQL_EXECUTE_TIMEOUT(40031, "SQL执行超时"),
```

- [ ] **Step 2: 创建 SqlExecuteTool.java**

```java
package com.chiho.wuagentscope.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 只读 SQL 执行工具
 * <p>
 * 安全机制：
 * - 仅允许 SELECT 语句
 * - 关键字黑名单（INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE/GRANT）
 * - 自动追加 LIMIT 1000（防止大结果集）
 * - 只读事务 + 30s 超时
 *
 * @author ChiHo
 */
@Component
@Slf4j
public class SqlExecuteTool {

    private static final int MAX_ROWS = 1000;
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "GRANT",
            "REVOKE", "REPLACE", "MERGE", "CALL", "EXEC", "EXECUTE"
    );

    private final DataSource dataSource;

    public SqlExecuteTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "execute_sql_query",
          description = "当需要查询数据库获取数据时，调用此工具执行 SELECT 语句。" +
                       "仅支持 SELECT，禁止任何写操作。返回查询结果 JSON 数组。")
    public String executeQuery(
            @ToolParam(name = "sql", description = "要执行的 SELECT SQL 语句") String sql) {
        log.info("##### ToolUse[SqlExecuteTool-execute_sql_query]: {}", sql);

        if (sql == null || sql.isBlank()) {
            return "Error: SQL is required";
        }

        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        // 安全校验
        if (!upper.startsWith("SELECT")) {
            return "Error: 仅支持 SELECT 查询，不支持其他操作";
        }
        for (String kw : FORBIDDEN_KEYWORDS) {
            if (upper.contains(kw)) {
                return "Error: SQL 中包含禁止的操作关键字: " + kw;
            }
        }

        // 自动追加 LIMIT
        String finalSql = trimmed;
        if (!upper.contains("LIMIT")) {
            finalSql = trimmed.replaceAll(";\\s*$", "") + " LIMIT " + MAX_ROWS;
        }

        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(finalSql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                ps.setMaxRows(MAX_ROWS);
                try (ResultSet rs = ps.executeQuery()) {
                    return formatResultSet(rs, System.currentTimeMillis() - start);
                }
            }
        } catch (SQLException e) {
            log.error("SQL执行失败: {}", sql, e);
            return "Error: SQL执行失败 - " + e.getMessage();
        }
    }

    private String formatResultSet(ResultSet rs, long elapsedMs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        JSONArray columns = new JSONArray();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        JSONArray rows = new JSONArray();
        int rowCount = 0;
        while (rs.next() && rowCount < MAX_ROWS) {
            JSONArray row = new JSONArray();
            for (int i = 1; i <= colCount; i++) {
                Object val = rs.getObject(i);
                row.add(val);
            }
            rows.add(row);
            rowCount++;
        }

        JSONObject result = new JSONObject();
        result.set("columns", columns);
        result.set("rows", rows);
        result.set("row_count", rowCount);
        result.set("execution_time_ms", elapsedMs);
        return result.toString();
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/tools/SqlExecuteTool.java
git add src/main/java/com/chiho/wuagentscope/common/exception/ErrorCode.java
git commit -m "feat(tools): add SqlExecuteTool with read-only SQL execution and safety checks"
```

---

### Task 3: ChartSuggestTool — 图表配置生成工具

**Files:**
- Create: `src/main/java/com/chiho/wuagentscope/tools/ChartSuggestTool.java`

**Interfaces:**
- Consumes: 查询结果 JSON（columns + rows）+ 用户原始问题
- Produces: `suggest_chart_config` Tool，返回 ECharts option JSON

- [ ] **Step 1: 创建 ChartSuggestTool.java**

```java
package com.chiho.wuagentscope.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 图表配置生成工具
 * <p>
 * 分析查询结果的数据特征（维度数、指标数、基数），
 * 推荐合适的图表类型并生成 ECharts option JSON。
 *
 * @author ChiHo
 */
@Component
@Slf4j
public class ChartSuggestTool {

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(date|time|year|month|day|created|updated|日期|时间|年|月|日)", Pattern.CASE_INSENSITIVE);

    @Tool(name = "suggest_chart_config",
          description = "当查询结果需要可视化展示时，调用此工具分析数据特征并生成 ECharts 图表配置。" +
                       "返回 ECharts option JSON，前端可直接渲染。")
    public String suggestChart(
            @ToolParam(name = "data", description = "查询结果 JSON，格式：{\"columns\":[...],\"rows\":[[...],...]}") String data,
            @ToolParam(name = "question", description = "用户的原始问题，用于生成图表标题") String question) {
        log.info("##### ToolUse[ChartSuggestTool-suggest_chart_config]: question={}", question);

        try {
            JSONObject dataObj = JSONUtil.parseObj(data);
            JSONArray columns = dataObj.getJSONArray("columns");
            JSONArray rows = dataObj.getJSONArray("rows");

            if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
                return "Error: 数据为空，无法生成图表";
            }

            int colCount = columns.size();
            int rowCount = rows.size();

            // 分析列类型：维度 vs 指标
            List<String> dimensions = new ArrayList<>();
            List<String> metrics = new ArrayList<>();
            boolean hasTimeDim = false;

            for (int i = 0; i < colCount; i++) {
                String colName = columns.getStr(i);
                boolean isMetric = isNumericColumn(rows, i);
                if (isMetric) {
                    metrics.add(colName);
                } else {
                    dimensions.add(colName);
                    if (TIME_PATTERN.matcher(colName).find()) {
                        hasTimeDim = true;
                    }
                }
            }

            // 推断图表类型并生成配置
            String chartType;
            JSONObject echartsOption;

            if (hasTimeDim && metrics.size() >= 1) {
                chartType = "line";
                echartsOption = buildLineChart(columns, rows, dimensions, metrics);
            } else if (dimensions.size() == 1 && metrics.size() == 1) {
                if (rowCount <= 10) {
                    chartType = "pie";
                    echartsOption = buildPieChart(columns, rows, dimensions.get(0), metrics.get(0));
                } else {
                    chartType = "bar";
                    echartsOption = buildBarChart(columns, rows, dimensions.get(0), metrics.get(0), true);
                }
            } else if (dimensions.size() >= 2 && metrics.size() >= 1) {
                chartType = "bar";
                echartsOption = buildGroupedBarChart(columns, rows, dimensions, metrics);
            } else {
                chartType = "bar";
                echartsOption = buildBarChart(columns, rows,
                        columns.getStr(0), metrics.isEmpty() ? columns.getStr(colCount - 1) : metrics.get(0), false);
            }

            String title = (question != null && !question.isBlank()) ? question.trim() : "数据查询结果";
            echartsOption.set("title", new JSONObject().set("text", title));

            JSONObject result = new JSONObject();
            result.set("chartType", chartType);
            result.set("title", title);
            result.set("echartsOption", echartsOption);
            return result.toString();

        } catch (Exception e) {
            log.error("生成图表配置失败", e);
            return "Error: " + e.getMessage();
        }
    }

    private boolean isNumericColumn(JSONArray rows, int colIndex) {
        int checkCount = Math.min(rows.size(), 10);
        int numericCount = 0;
        for (int i = 0; i < checkCount; i++) {
            Object val = rows.getJSONArray(i).get(colIndex);
            if (val instanceof Number) numericCount++;
        }
        return numericCount >= checkCount / 2;
    }

    private JSONObject buildPieChart(JSONArray columns, JSONArray rows, String dimCol, String metricCol) {
        int dimIdx = columns.indexOf(dimCol);
        int metricIdx = columns.indexOf(metricCol);
        JSONArray pieData = new JSONArray();
        for (int i = 0; i < rows.size(); i++) {
            JSONArray row = rows.getJSONArray(i);
            JSONObject item = new JSONObject();
            item.set("name", row.getStr(dimIdx));
            item.set("value", row.get(metricIdx));
            pieData.add(item);
        }
        JSONObject option = new JSONObject();
        option.set("tooltip", new JSONObject().set("trigger", "item"));
        option.set("series", List.of(
                new JSONObject().set("type", "pie").set("radius", "50%").set("data", pieData)));
        return option;
    }

    private JSONObject buildBarChart(JSONArray columns, JSONArray rows, String dimCol, String metricCol, boolean horizontal) {
        int dimIdx = columns.indexOf(dimCol);
        int metricIdx = columns.indexOf(metricCol);
        List<String> categories = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONArray row = rows.getJSONArray(i);
            categories.add(row.getStr(dimIdx));
            values.add(row.get(metricIdx));
        }
        JSONObject option = new JSONObject();
        option.set("tooltip", new JSONObject());
        if (horizontal) {
            option.set("xAxis", new JSONObject().set("type", "value"));
            option.set("yAxis", new JSONObject().set("type", "category").set("data", categories));
            option.set("series", List.of(new JSONObject().set("type", "bar").set("data", values)));
        } else {
            option.set("xAxis", new JSONObject().set("type", "category").set("data", categories));
            option.set("yAxis", new JSONObject().set("type", "value"));
            option.set("series", List.of(new JSONObject().set("type", "bar").set("data", values)));
        }
        return option;
    }

    private JSONObject buildLineChart(JSONArray columns, JSONArray rows, List<String> dimensions, List<String> metrics) {
        int dimIdx = columns.indexOf(dimensions.get(0));
        List<String> categories = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            categories.add(rows.getJSONArray(i).getStr(dimIdx));
        }
        List<JSONObject> seriesList = new ArrayList<>();
        for (String metric : metrics) {
            int metricIdx = columns.indexOf(metric);
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                values.add(rows.getJSONArray(i).get(metricIdx));
            }
            seriesList.add(new JSONObject().set("name", metric).set("type", "line").set("data", values));
        }
        JSONObject option = new JSONObject();
        option.set("tooltip", new JSONObject().set("trigger", "axis"));
        option.set("xAxis", new JSONObject().set("type", "category").set("data", categories));
        option.set("yAxis", new JSONObject().set("type", "value"));
        option.set("series", seriesList);
        return option;
    }

    private JSONObject buildGroupedBarChart(JSONArray columns, JSONArray rows, List<String> dimensions, List<String> metrics) {
        int dimIdx = columns.indexOf(dimensions.get(0));
        List<String> categories = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            categories.add(rows.getJSONArray(i).getStr(dimIdx));
        }
        List<JSONObject> seriesList = new ArrayList<>();
        for (String metric : metrics) {
            int metricIdx = columns.indexOf(metric);
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                values.add(rows.getJSONArray(i).get(metricIdx));
            }
            seriesList.add(new JSONObject().set("name", metric).set("type", "bar").set("data", values));
        }
        JSONObject option = new JSONObject();
        option.set("tooltip", new JSONObject().set("trigger", "axis"));
        option.set("legend", new JSONObject());
        option.set("xAxis", new JSONObject().set("type", "category").set("data", categories));
        option.set("yAxis", new JSONObject().set("type", "value"));
        option.set("series", seriesList);
        return option;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/tools/ChartSuggestTool.java
git commit -m "feat(tools): add ChartSuggestTool for ECharts config generation"
```

---

### Task 4: AgentScopeConfig Tool Groups 注册

**Files:**
- Modify: `src/main/java/com/chiho/wuagentscope/config/AgentScopeConfig.java`

**Interfaces:**
- Consumes: SchemaInspectorTool, SqlExecuteTool, ChartSuggestTool（Task 1-3）
- Produces: "general" 和 "data" 两个 Tool Group，供 SpecialistAgentRegistry 使用

- [ ] **Step 1: 修改 AgentScopeConfig.toolkit()**

将现有的 `toolkit()` 方法替换为使用 Tool Groups 的版本。在文件顶部添加 import：

```java
import com.chiho.wuagentscope.tools.ChartSuggestTool;
import com.chiho.wuagentscope.tools.SchemaInspectorTool;
import com.chiho.wuagentscope.tools.SqlExecuteTool;
```

将 `toolkit()` 方法的参数列表和方法体替换为：

```java
@Bean
public Toolkit toolkit(TimeTool timeTool, ImageSearchTool imageSearchTool,
                       WebSearchTool webSearchTool, WebReaderTool webReaderTool,
                       TokenUsageTool tokenUsageTool,
                       SchemaInspectorTool schemaInspectorTool,
                       SqlExecuteTool sqlExecuteTool,
                       ChartSuggestTool chartSuggestTool) {
    Toolkit toolkit = new Toolkit();

    // 创建工具组
    toolkit.createToolGroup("general", "通用工具", true);
    toolkit.createToolGroup("data", "数据分析工具", true);

    // 注册通用工具到 general 组
    toolkit.registration().tool(timeTool).group("general").apply();
    toolkit.registration().tool(imageSearchTool).group("general").apply();
    toolkit.registration().tool(webSearchTool).group("general").apply();
    toolkit.registration().tool(webReaderTool).group("general").apply();
    toolkit.registration().tool(tokenUsageTool).group("general").apply();

    // 注册数据分析工具到 data 组
    toolkit.registration().tool(schemaInspectorTool).group("data").apply();
    toolkit.registration().tool(sqlExecuteTool).group("data").apply();
    toolkit.registration().tool(chartSuggestTool).group("data").apply();

    return toolkit;
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/config/AgentScopeConfig.java
git commit -m "feat(config): register data analysis tools with Tool Groups in AgentScopeConfig"
```

---

### Task 5: HarnessAgentConfig 同步 Tool Groups 注册

**Files:**
- Modify: `src/main/java/com/chiho/wuagentscope/config/HarnessAgentConfig.java`

**Interfaces:**
- Consumes: 同 Task 4 的 3 个新 Tool
- Produces: harness 模式下的 "general" 和 "data" Tool Groups

- [ ] **Step 1: 修改 HarnessAgentConfig.harnessToolkit()**

在文件顶部添加 import：

```java
import com.chiho.wuagentscope.tools.ChartSuggestTool;
import com.chiho.wuagentscope.tools.SchemaInspectorTool;
import com.chiho.wuagentscope.tools.SqlExecuteTool;
```

将 `harnessToolkit()` 方法的参数列表和方法体替换为：

```java
@Bean
public Toolkit harnessToolkit(TimeTool timeTool, ImageSearchTool imageSearchTool,
                              WebSearchTool webSearchTool, WebReaderTool webReaderTool,
                              TokenUsageTool tokenUsageTool,
                              SchemaInspectorTool schemaInspectorTool,
                              SqlExecuteTool sqlExecuteTool,
                              ChartSuggestTool chartSuggestTool) {
    Toolkit toolkit = new Toolkit();

    // 创建工具组
    toolkit.createToolGroup("general", "通用工具", true);
    toolkit.createToolGroup("data", "数据分析工具", true);

    // 注册通用工具到 general 组
    toolkit.registration().tool(timeTool).group("general").apply();
    toolkit.registration().tool(imageSearchTool).group("general").apply();
    toolkit.registration().tool(webSearchTool).group("general").apply();
    toolkit.registration().tool(webReaderTool).group("general").apply();
    toolkit.registration().tool(tokenUsageTool).group("general").apply();

    // 注册数据分析工具到 data 组
    toolkit.registration().tool(schemaInspectorTool).group("data").apply();
    toolkit.registration().tool(sqlExecuteTool).group("data").apply();
    toolkit.registration().tool(chartSuggestTool).group("data").apply();

    return toolkit;
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/config/HarnessAgentConfig.java
git commit -m "feat(config): sync data analysis tools with Tool Groups in HarnessAgentConfig"
```

---

### Task 6: MessageVO 新增 chartData 字段

**Files:**
- Modify: `src/main/java/com/chiho/wuagentscope/model/MessageVO.java`

**Interfaces:**
- Consumes: Agent 返回的文本（包含图表 JSON）
- Produces: `chartData` 字段，前端用于 ECharts 渲染

- [ ] **Step 1: 在 MessageVO 中添加 chartData 字段**

在 `imageUrls` 字段后添加：

```java
/** 图表数据（ECharts option JSON，由 ChartSuggestTool 生成） */
private String chartData;
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/model/MessageVO.java
git commit -m "feat(model): add chartData field to MessageVO for ECharts rendering"
```

---

### Task 7: ChatConversationService 图表数据解析

**Files:**
- Modify: `src/main/java/com/chiho/wuagentscope/service/ChatConversationService.java`

**Interfaces:**
- Consumes: `Msg.getTextContent()`（Agent 返回的原始文本）
- Produces: `MessageVO.chartData`（解析出的 ECharts JSON）

- [ ] **Step 1: 修改 toMessageVO() 方法**

在 `toMessageVO()` 方法中，`vo.setContent(msg.getTextContent())` 之后添加图表数据解析逻辑：

```java
// 解析图表数据：Agent 返回的文本中如果包含 suggest_chart_config 工具的结果，
// 提取其中的 echartsOption 部分设置到 chartData
String textContent = msg.getTextContent();
vo.setContent(textContent);
vo.setChartData(extractChartData(textContent));
```

然后在 `ChatConversationService` 类中添加私有方法：

```java
/**
 * 从 Agent 响应文本中提取图表数据
 * <p>
 * ChartSuggestTool 返回格式：{"chartType":"bar","title":"...","echartsOption":{...}}
 * Agent 可能将其嵌入文本中，需要提取。
 */
private String extractChartData(String text) {
    if (text == null || text.isBlank()) {
        return null;
    }
    // 查找 suggest_chart_config 工具返回的 JSON 块
    int start = text.indexOf("{\"chartType\"");
    if (start < 0) {
        start = text.indexOf("{\"charttype\"");
    }
    if (start < 0) {
        return null;
    }
    // 找到匹配的闭合大括号
    int depth = 0;
    for (int i = start; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '{') depth++;
        else if (c == '}') {
            depth--;
            if (depth == 0) {
                String json = text.substring(start, i + 1);
                // 验证是否包含 echartsOption
                if (json.contains("echartsOption")) {
                    return json;
                }
                return null;
            }
        }
    }
    return null;
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/service/ChatConversationService.java
git commit -m "feat(service): extract chartData from agent response in toMessageVO"
```

---

### Task 8: RouteResult 路由结果模型

**Files:**
- Create: `src/main/java/com/chiho/wuagentscope/model/RouteResult.java`

**Interfaces:**
- Produces: `RouteResult` record，供 AgentRouterService 和 ChatService 使用

- [ ] **Step 1: 创建 RouteResult.java**

```java
package com.chiho.wuagentscope.model;

/**
 * 路由分类结果
 *
 * @param route      路由目标：general / data_analyst
 * @param confidence 分类置信度：0.0 ~ 1.0
 * @param reason     分类原因说明
 * @author ChiHo
 */
public record RouteResult(String route, double confidence, String reason) {

    /** 默认路由（置信度不足时回退） */
    public static RouteResult defaultRoute() {
        return new RouteResult("general", 1.0, "默认路由");
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/model/RouteResult.java
git commit -m "feat(model): add RouteResult record for agent routing"
```

---

### Task 9: AgentRouterService 意图分类服务

**Files:**
- Create: `src/main/java/com/chiho/wuagentscope/service/AgentRouterService.java`

**Interfaces:**
- Consumes: `ModelAgentRegistry`（获取 qwen3-vl:8b 模型）、`AgentStateStore`
- Produces: `AgentRouterService.route(String message)` → `RouteResult`

- [ ] **Step 1: 创建 AgentRouterService.java**

```java
package com.chiho.wuagentscope.service;

import com.chiho.wuagentscope.config.ModelAgentRegistry;
import com.chiho.wuagentscope.model.RouteResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.formatter.ollama.OllamaChatFormatter;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 路由服务
 * <p>
 * 使用轻量模型（qwen3-vl:8b）对用户消息做意图分类，
 * 返回路由结果（general / data_analyst），供 ChatService 选择对应的专业 Agent。
 * <p>
 * 路由策略：confidence >= 0.8 直接路由，否则回退到 general。
 *
 * @author ChiHo
 */
@Service
@Profile("!harness")
@Slf4j
public class AgentRouterService {

    private static final String ROUTER_PROMPT = """
            你是一个意图分类系统。根据用户消息，判断应该由哪个模块处理。

            可选路由：
            - general: 通用对话、闲聊、简单问答、翻译、总结、知识问答、编程帮助
            - data_analyst: 数据查询、SQL、报表、统计分析、图表可视化、数据库相关问题

            只输出 JSON，不要输出其他内容：
            {"route": "general", "confidence": 0.95, "reason": "用户在闲聊"}
            """;

    private static final double CONFIDENCE_THRESHOLD = 0.8;

    @Value("${agentscope.router.model-id:qwen3-vl:8b}")
    private String routerModelId;

    private final AgentStateStore agentStateStore;
    private final Toolkit toolkit;

    private ReActAgent routerAgent;

    public AgentRouterService(AgentStateStore agentStateStore, Toolkit toolkit) {
        this.agentStateStore = agentStateStore;
        this.toolkit = toolkit;
    }

    @PostConstruct
    public void init() {
        // 使用 qwen3-vl:8b 创建轻量 Router Agent
        OllamaChatModel model = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen3-vl:8b")
                .formatter(new OllamaChatFormatter())
                .build();

        routerAgent = ReActAgent.builder()
                .name("router")
                .sysPrompt(ROUTER_PROMPT)
                .model(model)
                .stateStore(agentStateStore)
                .toolkit(toolkit)
                .maxIters(1)
                .build();

        log.info("Router Agent 初始化完成: model=qwen3-vl:8b");
    }

    /**
     * 对用户消息做意图分类
     *
     * @param userMessage 用户输入
     * @return 路由结果
     */
    public RouteResult route(String userMessage) {
        try {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("router")
                    .sessionId("router-" + System.currentTimeMillis())
                    .build();

            String result = routerAgent.call(List.of(new UserMessage(userMessage)), ctx)
                    .block()
                    .getTextContent();

            return parseRouteResult(result);
        } catch (Exception e) {
            log.warn("路由分类失败，回退到 general: {}", e.getMessage());
            return RouteResult.defaultRoute();
        }
    }

    private RouteResult parseRouteResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return RouteResult.defaultRoute();
        }

        try {
            // 提取 JSON 块（Router 可能返回 markdown 代码块包裹的 JSON）
            String json = raw.trim();
            if (json.contains("{")) {
                json = json.substring(json.indexOf("{"), json.lastIndexOf("}") + 1);
            }

            cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(json);
            String route = obj.getStr("route", "general");
            double confidence = obj.getDouble("confidence", 0.0);
            String reason = obj.getStr("reason", "");

            // 校验路由值
            if (!"data_analyst".equals(route)) {
                route = "general";
            }

            // 置信度不足时回退
            if (confidence < CONFIDENCE_THRESHOLD) {
                log.info("路由置信度不足: route={}, confidence={}, 回退到 general", route, confidence);
                route = "general";
            }

            log.info("路由结果: route={}, confidence={}, reason={}", route, confidence, reason);
            return new RouteResult(route, confidence, reason);

        } catch (Exception e) {
            log.warn("解析路由结果失败: {}, 回退到 general", raw, e);
            return RouteResult.defaultRoute();
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/service/AgentRouterService.java
git commit -m "feat(service): add AgentRouterService with lightweight model intent classification"
```

---

### Task 10: SpecialistAgentRegistry 专业 Agent 管理

**Files:**
- Create: `src/main/java/com/chiho/wuagentscope/config/SpecialistAgentRegistry.java`
- Modify: `src/main/java/com/chiho/wuagentscope/config/ModelAgentRegistry.java`

**Interfaces:**
- Consumes: `ModelAgentRegistry`（获取 OllamaChatModel）、Toolkit、中间件
- Produces: `SpecialistAgentRegistry.getAgent(route, modelId)` → `ReActAgent`

- [ ] **Step 1: 在 ModelAgentRegistry 中暴露 getModel() 方法**

在 `ModelAgentRegistry.java` 中添加一个公共方法，供 SpecialistAgentRegistry 获取底层模型实例：

```java
/**
 * 获取指定模型的 OllamaChatModel 实例（供 SpecialistAgentRegistry 构建专业 Agent）
 */
public OllamaChatModel getModel(String modelId) {
    String resolved = modelId != null ? modelId : defaultModel;
    ModelConfig config = configMap.get(resolved);
    if (config == null) {
        throw new BusinessException(ErrorCode.MODEL_NOT_FOUND, "不支持的模型: " + resolved);
    }
    return createOllamaModel(config);
}
```

- [ ] **Step 2: 创建 SpecialistAgentRegistry.java**

```java
package com.chiho.wuagentscope.config;

import com.chiho.wuagentscope.middleware.ContextTrimMiddleware;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 专业 Agent 注册中心
 * <p>
 * 管理不同领域专业 Agent 的创建和查找。每个专业 Agent 有独立的 system prompt，
 * 通过 Tool Groups 控制可见的工具集。保留 ModelAgentRegistry 供用户手动选择模型。
 * <p>
 * Agent 缓存 key 格式："{route}:{modelId}"，避免重复创建。
 *
 * @author ChiHo
 */
@Configuration
@Profile("!harness")
@Slf4j
public class SpecialistAgentRegistry {

    private final ModelAgentRegistry modelRegistry;
    private final AgentStateStore agentStateStore;
    private final Toolkit toolkit;
    private final ContextTrimMiddleware contextTrimMiddleware;
    private final OtelTracingMiddleware otelTracingMiddleware;
    private final DynamicSkillMiddleware dynamicSkillMiddleware;

    /** Agent 缓存：key = "route:modelId" */
    private final Map<String, ReActAgent> agentCache = new ConcurrentHashMap<>();

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

    public SpecialistAgentRegistry(ModelAgentRegistry modelRegistry,
                                   AgentStateStore agentStateStore,
                                   Toolkit toolkit,
                                   ContextTrimMiddleware contextTrimMiddleware,
                                   OtelTracingMiddleware otelTracingMiddleware,
                                   DynamicSkillMiddleware dynamicSkillMiddleware) {
        this.modelRegistry = modelRegistry;
        this.agentStateStore = agentStateStore;
        this.toolkit = toolkit;
        this.contextTrimMiddleware = contextTrimMiddleware;
        this.otelTracingMiddleware = otelTracingMiddleware;
        this.dynamicSkillMiddleware = dynamicSkillMiddleware;
    }

    /**
     * 获取专业 Agent（带缓存）
     *
     * @param route   路由类型：general / data_analyst
     * @param modelId 用户选定的模型 ID
     * @return 对应的 ReActAgent 实例
     */
    public ReActAgent getAgent(String route, String modelId) {
        String cacheKey = route + ":" + modelId;
        return agentCache.computeIfAbsent(cacheKey, key -> {
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

    private ReActAgent buildGeneralAgent(OllamaChatModel model) {
        // 复用 ModelAgentRegistry 的通用 Agent 配置
        return modelRegistry.getAgent(null);
    }

    private ReActAgent buildDataAnalystAgent(OllamaChatModel model) {
        log.info("创建 data_analyst Agent: model={}", model.getModelName());
        return ReActAgent.builder()
                .name("data-analyst")
                .sysPrompt(DATA_ANALYST_PROMPT)
                .model(model)
                .stateStore(agentStateStore)
                .toolkit(toolkit)
                .activatedToolGroups(List.of("general", "data"))
                .middleware(contextTrimMiddleware)
                .middleware(otelTracingMiddleware)
                .middleware(dynamicSkillMiddleware)
                .maxIters(20)
                .build();
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/config/SpecialistAgentRegistry.java
git add src/main/java/com/chiho/wuagentscope/config/ModelAgentRegistry.java
git commit -m "feat(config): add SpecialistAgentRegistry with data_analyst agent and Tool Groups"
```

---

### Task 11: ChatService 接入 Router

**Files:**
- Modify: `src/main/java/com/chiho/wuagentscope/service/ChatService.java`

**Interfaces:**
- Consumes: `AgentRouterService.route()`（Task 9）、`SpecialistAgentRegistry.getAgent()`（Task 10）
- Produces: 改造后的 `chatStream()` 方法，先路由再调用对应 Agent

- [ ] **Step 1: 修改 ChatService**

在 ChatService 中注入新依赖，并改造 `chatStream()` 核心方法。

添加字段：

```java
@Resource
private AgentRouterService routerService;

@Resource
private SpecialistAgentRegistry specialistAgentRegistry;
```

将 `chatStream(Long userId, String sessionId, String message, String modelId, List<String> imageUrls, List<ImageData> images)` 方法体替换为：

```java
public Flux<String> chatStream(Long userId, String sessionId, String message,
                               String modelId, List<String> imageUrls, List<ImageData> images) {
    // 1. Router 意图分类
    RouteResult route = routerService.route(message);
    log.info("路由结果: userId={}, route={}, confidence={}", userId, route.route(), route.confidence());

    // 2. 选择专业 Agent（传入用户选定的 modelId）
    ReActAgent agent = specialistAgentRegistry.getAgent(route.route(), modelId);

    // 3. 构建上下文并调用
    RuntimeContext ctx = buildContext(userId, sessionId);
    UserMessage userMsg = buildUserMessage(message, imageUrls, images);

    Flux<AgentEvent> rawEvents = agent.streamEvents(List.of(userMsg), ctx);
    Flux<AgentEvent> observedEvents = observabilityEventSink.wrapStream(rawEvents, userId, sessionId, modelId);

    return observedEvents
            .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
            .map(event -> ((TextBlockDeltaEvent) event).getDelta())
            .doOnError(e -> log.error("流式聊天异常: userId={}, sessionId={}, modelId={}", userId, sessionId, modelId, e));
}
```

同样改造 `chatStreamWithEvents()` 和 `chat()` 方法，加入相同的路由逻辑。

添加 import：

```java
import com.chiho.wuagentscope.model.RouteResult;
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/service/ChatService.java
git commit -m "feat(service): integrate AgentRouterService and SpecialistAgentRegistry into ChatService"
```

---

### Task 12: AiController modelId 参数可选化

**Files:**
- Modify: `src/main/java/com/chiho/wuagentscope/controller/AiController.java`

**Interfaces:**
- 无新接口，仅将现有 `modelId` 参数从必填改为可选

- [ ] **Step 1: 修改 AiController**

`modelId` 参数在 GET 接口中已经是 `@RequestParam(required = false)`，无需修改。

在 POST 接口的 `ChatRequest` 中，`modelId` 也是可选字段（因为 Router 会自动选择 Agent），无需修改 Controller 代码。

确认 `ChatRequest.modelId` 字段无 `@NotBlank` 等必填校验即可。

如果 `ChatRequest` 中有必填校验，移除 `modelId` 的 `@NotBlank` 注解。

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/chiho/wuagentscope/controller/AiController.java
git commit -m "feat(controller): make modelId optional in chat endpoints"
```

---

### Task 13: data-analyst Skill 文件

**Files:**
- Create: `skills/data-analyst/SKILL.md`

**Interfaces:**
- 被 `DynamicSkillMiddleware` 自动加载，注入到 data_analyst Agent 的 system prompt

- [ ] **Step 1: 创建 SKILL.md**

```markdown
---
name: data-analyst
description: 数据分析 Skill，提供 SQL 查询和数据可视化的专业指导
---

# 数据分析助手

你是一个专业的数据分析助手。当用户询问数据相关问题时，按以下流程操作：

## 工作流程

1. **理解需求**：分析用户想要什么数据，涉及哪些维度和指标
2. **查看表结构**：调用 `inspect_database_schema` 了解可用的表和字段
3. **编写 SQL**：根据需求编写 SELECT 查询语句
4. **执行查询**：调用 `execute_sql_query` 获取数据
5. **可视化（可选）**：如果用户需要图表，调用 `suggest_chart_config` 生成 ECharts 配置
6. **总结回答**：用简洁的中文总结数据发现

## SQL 编写规范

- 只使用 SELECT 语句
- 使用中文别名让结果更易读（如 `SELECT product_name AS 产品名称`）
- 复杂查询可以分步执行，先验证子查询结果
- 合理使用 GROUP BY、ORDER BY、LIMIT
- 日期过滤使用标准格式：`WHERE created_at >= '2026-06-01'`

## 图表选择指南

| 场景 | 推荐图表 |
|------|---------|
| 占比分析 | 饼图 |
| 排名对比 | 柱状图（横向） |
| 趋势变化 | 折线图 |
| 多维对比 | 分组柱状图 |

## 注意事项

- 不要尝试 INSERT/UPDATE/DELETE 等写操作
- 查询结果超过 1000 行会自动截断
- 如果 SQL 执行失败，检查语法后重试
- 敏感字段（如密码）不要查询
```

- [ ] **Step 2: 提交**

```bash
git add skills/data-analyst/SKILL.md
git commit -m "feat(skill): add data-analyst SKILL.md for specialized data analysis guidance"
```

---

### Task 14: 端到端验证

**Files:**
- 无新增/修改文件

**Interfaces:**
- 验证所有 Task 的集成是否正常工作

- [ ] **Step 1: 全量编译**

Run: `./mvnw clean compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 启动应用**

Run: `./mvnw spring-boot:run`
Expected: 应用启动成功，日志中出现：
- `注册模型: id=qwen3.5:9b ...`
- `注册模型: id=qwen3:14b ...`
- `Router Agent 初始化完成: model=qwen3-vl:8b`

- [ ] **Step 3: 测试数据分析功能**

使用 curl 或浏览器测试：

```bash
# 测试数据分析路由（POST 方式）
curl -X POST http://localhost:8133/api/ai/chat/common/sse \
  -H "Content-Type: application/json" \
  -d '{"token":"YOUR_TOKEN","chatId":"test-data","message":"帮我查一下数据库里有哪些表","modelId":"qwen3.5:9b"}'
```

Expected: Agent 调用 `inspect_database_schema` 工具，返回表列表。

- [ ] **Step 4: 测试 SQL 查询**

```bash
curl -X POST http://localhost:8133/api/ai/chat/common/sse \
  -H "Content-Type: application/json" \
  -d '{"token":"YOUR_TOKEN","chatId":"test-sql","message":"查询每个用户的 token 使用总量，按总量降序排列","modelId":"qwen3.5:9b"}'
```

Expected: Agent 调用 `inspect_database_schema` → `execute_sql_query`，返回查询结果。

- [ ] **Step 5: 测试图表生成**

```bash
curl -X POST http://localhost:8133/api/ai/chat/common/sse \
  -H "Content-Type: application/json" \
  -d '{"token":"YOUR_TOKEN","chatId":"test-chart","message":"用柱状图展示每个用户的 token 使用量","modelId":"qwen3.5:9b"}'
```

Expected: Agent 调用 `inspect_database_schema` → `execute_sql_query` → `suggest_chart_config`，返回包含 ECharts 配置的响应。

- [ ] **Step 6: 测试 Router 路由**

```bash
# 通用问题 → 应该走 general Agent
curl -X POST http://localhost:8133/api/ai/chat/common/sse \
  -H "Content-Type: application/json" \
  -d '{"token":"YOUR_TOKEN","chatId":"test-general","message":"你好，今天天气怎么样？","modelId":"qwen3.5:9b"}'
```

Expected: 日志中出现 `路由结果: userId=xxx, route=general, confidence=xxx`

- [ ] **Step 7: 验证历史消息图表数据**

访问会话历史消息接口，确认返回的 MessageVO 中包含 `chartData` 字段：

```bash
curl "http://localhost:8133/api/chat/messages?chatId=test-chart&token=YOUR_TOKEN"
```

Expected: 最后一条 assistant 消息包含 `chartData` 字段，值为 ECharts option JSON。

- [ ] **Step 8: 最终提交**

```bash
git add -A
git commit -m "feat: complete data analysis and agent routing implementation"
```

---

## Self-Review Checklist

- [x] 所有 spec 中的功能都有对应 Task 覆盖
- [x] 无 TBD/TODO 占位符
- [x] 类型/方法名/参数在各 Task 间一致
- [x] 安全边界已实现（SQL 白名单、表名校验、只读事务）
- [x] 前后兼容（modelId 可选、general Agent 回退）

---

**Plan complete and saved to `docs/superpowers/plans/2026-06-21-data-analysis-routing-plan.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - 我为每个 Task 分派独立子代理，Task 间审查，快速迭代

**2. Inline Execution** - 在当前会话中逐 Task 执行，带检查点

**选择哪种方式？**
