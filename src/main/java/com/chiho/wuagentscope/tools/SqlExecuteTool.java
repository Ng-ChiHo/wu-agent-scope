package com.chiho.wuagentscope.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 查询执行工具
 * <p>
 * 仅支持 SELECT 查询，禁止任何写操作。
 * 返回查询结果 JSON 数组，包含 columns、rows、row_count、execution_time_ms。
 *
 * @author Chiho
 */
@Component
@Slf4j
public class SqlExecuteTool {

    private final DataSource dataSource;

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
            "CREATE", "GRANT", "REVOKE", "REPLACE", "MERGE", "CALL",
            "EXEC", "EXECUTE"
    );

    /**
     * 匹配 SQL 中的危险关键词（独立单词，忽略大小写）
     */
    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "(?i)\\b(?:INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|REPLACE|MERGE|CALL|EXEC|EXECUTE)\\b"
    );

    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "(?i)\\bLIMIT\\s+\\d+"
    );

    public SqlExecuteTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "execute_sql_query", description = "当需要查询数据库获取数据时，调用此工具执行 SELECT 语句。仅支持 SELECT，禁止任何写操作。返回查询结果 JSON 数组。")
    public String executeSqlQuery(@ToolParam(name = "sql", description = "要执行的 SELECT SQL 语句") String sql) {
        log.info("##### ToolUse[SqlExecuteTool-execute_sql_query]: {}", sql);

        // 1. 基本校验
        if (sql == null || sql.isBlank()) {
            return "Error: SQL 语句不能为空";
        }

        String trimmedSql = sql.strip();

        // 2. 只允许 SELECT 开头的查询
        if (!trimmedSql.toUpperCase().startsWith("SELECT")) {
            return "Error: 仅支持 SELECT 查询语句";
        }

        // 3. 检查危险关键词
        if (FORBIDDEN_PATTERN.matcher(trimmedSql).find()) {
            return "Error: SQL 包含禁止的操作（INSERT/UPDATE/DELETE/DROP 等），仅允许 SELECT 查询";
        }

        // 4. 自动追加 LIMIT 1000（如果未设置）
        if (!LIMIT_PATTERN.matcher(trimmedSql).find()) {
            trimmedSql = trimmedSql.replaceAll("(?i);\\s*$", "") + " LIMIT 1000";
        }

        long startTime = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);

            try (PreparedStatement ps = conn.prepareStatement(trimmedSql)) {
                ps.setQueryTimeout(30);
                ps.setMaxRows(1000);

                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();

                    // 构建列名数组
                    JSONArray columns = new JSONArray();
                    for (int i = 1; i <= columnCount; i++) {
                        columns.put(meta.getColumnLabel(i));
                    }

                    // 构建行数据
                    JSONArray rows = new JSONArray();
                    int rowCount = 0;
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = rs.getObject(i);
                            row.set(meta.getColumnLabel(i), value);
                        }
                        rows.put(row);
                        rowCount++;
                    }

                    long executionTimeMs = System.currentTimeMillis() - startTime;

                    // 构建返回 JSON
                    JSONObject result = new JSONObject();
                    result.set("columns", columns);
                    result.set("rows", rows);
                    result.set("row_count", rowCount);
                    result.set("execution_time_ms", executionTimeMs);

                    return result.toString();
                }
            }
        } catch (SQLException e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            if (executionTimeMs >= 30000) {
                log.error("SQL 执行超时: {}", trimmedSql, e);
                return "Error: SQL 执行超时（超过30秒）";
            }
            log.error("SQL 执行失败: {}", trimmedSql, e);
            return "Error: SQL 执行失败 - " + e.getMessage();
        }
    }
}
