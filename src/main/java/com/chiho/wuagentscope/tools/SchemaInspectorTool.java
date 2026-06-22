package com.chiho.wuagentscope.tools;

import cn.hutool.core.util.StrUtil;
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

/**
 * 数据库结构探查工具
 * <p>
 * 查询 information_schema 获取 agent_scope 数据库的表结构信息，
 * 供 LLM 在回答数据、报表、统计相关问题时先了解数据库结构。
 *
 * @author Chiho
 */
@Component
@Slf4j
public class SchemaInspectorTool {

    private static final String DB_NAME = "agent_scope";
    private static final String TABLE_NAME_REGEX = "^[a-zA-Z_][a-zA-Z0-9_]*$";

    private final DataSource dataSource;

    public SchemaInspectorTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "inspect_database_schema", description = "当用户询问数据、报表、统计相关问题时，先调用此工具获取数据库表结构。不传参数返回所有表概览，传入表名返回该表的字段详情。")
    public String inspectDatabaseSchema(
            @ToolParam(name = "table_name", description = "表名，可选。不传则返回所有表列表") String tableName
    ) {
        log.info("##### ToolUse[SchemaInspectorTool-inspect_database_schema]: tableName={}", tableName);

        if (StrUtil.isBlank(tableName)) {
            return listTables();
        }

        return describeTable(tableName.trim());
    }

    /**
     * 列出 agent_scope 数据库中所有用户表
     */
    private String listTables() {
        String sql = "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH "
                + "FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' "
                + "ORDER BY TABLE_NAME";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DB_NAME);

            try (ResultSet rs = ps.executeQuery()) {
                JSONArray tables = new JSONArray();

                while (rs.next()) {
                    JSONObject table = new JSONObject();
                    table.set("tableName", rs.getString("TABLE_NAME"));
                    table.set("comment", rs.getString("TABLE_COMMENT"));
                    table.set("rows", rs.getLong("TABLE_ROWS"));
                    table.set("dataSizeKB", rs.getLong("DATA_LENGTH") / 1024);
                    table.set("indexSizeKB", rs.getLong("INDEX_LENGTH") / 1024);
                    tables.add(table);
                }

                if (tables.isEmpty()) {
                    return "数据库 " + DB_NAME + " 中没有找到任何用户表";
                }

                return cn.hutool.json.JSONUtil.toJsonPrettyStr(tables);
            }
        } catch (Exception e) {
            log.error("查询数据库表列表失败", e);
            return "Error: 查询表列表失败 - " + e.getMessage();
        }
    }

    /**
     * 描述指定表的字段详情
     */
    private String describeTable(String tableName) {
        // 校验表名，防止 SQL 注入
        if (!tableName.matches(TABLE_NAME_REGEX)) {
            return "Error: 非法表名 '" + tableName + "'，表名只能包含字母、数字和下划线";
        }

        // 先检查表是否存在
        if (!tableExists(tableName)) {
            return "Error: 表 '" + tableName + "' 在数据库 " + DB_NAME + " 中不存在";
        }

        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, "
                + "COLUMN_DEFAULT, COLUMN_COMMENT, EXTRA "
                + "FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DB_NAME);
            ps.setString(2, tableName);

            try (ResultSet rs = ps.executeQuery()) {
                JSONArray columns = new JSONArray();

                while (rs.next()) {
                    JSONObject col = new JSONObject();
                    col.set("name", rs.getString("COLUMN_NAME"));
                    col.set("type", rs.getString("COLUMN_TYPE"));
                    col.set("nullable", "YES".equals(rs.getString("IS_NULLABLE")));
                    col.set("key", rs.getString("COLUMN_KEY"));
                    col.set("default", rs.getString("COLUMN_DEFAULT"));
                    col.set("comment", rs.getString("COLUMN_COMMENT"));
                    col.set("extra", rs.getString("EXTRA"));
                    columns.add(col);
                }

                JSONObject result = new JSONObject();
                result.set("table", tableName);
                result.set("columns", columns);

                return cn.hutool.json.JSONUtil.toJsonPrettyStr(result);
            }
        } catch (Exception e) {
            log.error("查询表结构失败: tableName={}", tableName, e);
            return "Error: 查询表结构失败 - " + e.getMessage();
        }
    }

    /**
     * 检查指定表是否存在于 agent_scope 数据库中
     */
    private boolean tableExists(String tableName) {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND TABLE_TYPE = 'BASE TABLE'";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DB_NAME);
            ps.setString(2, tableName);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            log.error("检查表是否存在失败: tableName={}", tableName, e);
            return false;
        }
    }
}
