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
 * 图表配置推荐工具
 * <p>
 * 分析 SQL 查询结果的数据特征（维度列、指标列、行数等），
 * 推断最合适的 ECharts 图表类型，并生成可直接渲染的 ECharts option JSON。
 * <p>
 * 推断规则：
 * - 时间维度 + 指标 → 折线图
 * - 1 维度 + 1 指标，行数 ≤ 10 → 饼图
 * - 1 维度 + 1 指标，行数 > 10 → 横向柱状图
 * - 2+ 维度 + 指标 → 分组柱状图
 * - 默认 → 柱状图
 *
 * @author Chiho
 */
@Component
@Slf4j
public class ChartSuggestTool {

    /**
     * 匹配时间维度列名（中英文）
     */
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?i)(date|time|year|month|day|created|updated|日期|时间|年|月|日)"
    );

    @Tool(name = "suggest_chart_config", description = "当查询结果需要可视化展示时，调用此工具分析数据特征并生成 ECharts 图表配置。返回 ECharts option JSON，前端可直接渲染。")
    public String suggestChartConfig(
            @ToolParam(name = "data", description = "查询结果 JSON 字符串，包含 columns 和 rows，格式与 execute_sql_query 返回值一致") String data,
            @ToolParam(name = "question", description = "用户的原始问题，用于生成图表标题") String question
    ) {
        log.info("##### ToolUse[ChartSuggestTool-suggest_chart_config]: question={}", question);

        try {
            // 1. 解析输入数据
            JSONObject dataObj = JSONUtil.parseObj(data);
            JSONArray columns = dataObj.getJSONArray("columns");
            JSONArray rows = dataObj.getJSONArray("rows");

            if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
                return errorResult("数据为空，无法生成图表");
            }

            List<String> colNames = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                colNames.add(columns.getStr(i));
            }

            int rowCount = rows.size();

            // 2. 分析列类型：区分维度列和指标列
            List<Integer> dimensionIndices = new ArrayList<>();
            List<Integer> metricIndices = new ArrayList<>();
            List<Integer> timeDimensionIndices = new ArrayList<>();

            for (int i = 0; i < colNames.size(); i++) {
                String colName = colNames.get(i);
                if (isTimeColumn(colName)) {
                    timeDimensionIndices.add(i);
                    dimensionIndices.add(i);
                } else if (isNumericColumn(rows, colName)) {
                    metricIndices.add(i);
                } else {
                    dimensionIndices.add(i);
                }
            }

            // 如果没有指标列，无法生成图表
            if (metricIndices.isEmpty()) {
                return errorResult("未找到数值型指标列，无法生成图表。建议查询中包含聚合函数如 COUNT/SUM/AVG 等。");
            }

            // 如果没有维度列，使用第一列作为默认维度
            if (dimensionIndices.isEmpty()) {
                dimensionIndices.add(0);
            }

            // 3. 推断图表类型
            String chartType;
            if (!timeDimensionIndices.isEmpty() && !metricIndices.isEmpty()) {
                chartType = "line";
            } else if (dimensionIndices.size() == 1 && metricIndices.size() == 1) {
                chartType = rowCount <= 10 ? "pie" : "bar-horizontal";
            } else if (dimensionIndices.size() >= 2) {
                chartType = "bar-grouped";
            } else {
                chartType = "bar";
            }

            // 4. 生成图表标题
            String title = (question != null && !question.isBlank()) ? question.trim() : "数据可视化";

            // 5. 构建 ECharts option
            JSONObject echartsOption = buildEchartsOption(chartType, colNames, rows, dimensionIndices, metricIndices, timeDimensionIndices, title);

            // 6. 组装返回结果
            JSONObject result = new JSONObject();
            result.set("chartType", chartType);
            result.set("title", new JSONObject().set("text", title));
            result.set("echartsOption", echartsOption);

            return cn.hutool.json.JSONUtil.toJsonPrettyStr(result);

        } catch (Exception e) {
            log.error("图表配置生成失败: question={}", question, e);
            return errorResult("图表配置生成失败: " + e.getMessage());
        }
    }

    /**
     * 判断列名是否为时间维度
     */
    private boolean isTimeColumn(String colName) {
        return colName != null && TIME_PATTERN.matcher(colName).find();
    }

    /**
     * 判断列是否为数值型（检查前10行，>=50%为Number即认为是数值列）
     */
    private boolean isNumericColumn(JSONArray rows, String colName) {
        int checkCount = Math.min(rows.size(), 10);
        if (checkCount == 0) return false;

        int numericCount = 0;
        for (int i = 0; i < checkCount; i++) {
            JSONObject row = rows.getJSONObject(i);
            Object value = row.get(colName);
            if (value instanceof Number) {
                numericCount++;
            } else if (value != null) {
                // 尝试解析字符串数字
                try {
                    Double.parseDouble(value.toString());
                    numericCount++;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return (numericCount * 100 / checkCount) >= 50;
    }

    /**
     * 根据图表类型构建 ECharts option JSON
     */
    private JSONObject buildEchartsOption(String chartType, List<String> colNames, JSONArray rows,
                                          List<Integer> dimensionIndices, List<Integer> metricIndices,
                                          List<Integer> timeDimensionIndices, String title) {
        JSONObject option = new JSONObject();
        option.set("title", new JSONObject().set("text", title).set("left", "center"));
        option.set("tooltip", buildTooltip(chartType));
        option.set("legend", buildLegend(chartType, metricIndices, colNames));
        option.set("grid", new JSONObject().set("left", "3%").set("right", "4%").set("bottom", "3%").set("containLabel", true));

        switch (chartType) {
            case "pie" -> buildPieOption(option, colNames, rows, dimensionIndices.get(0), metricIndices.get(0));
            case "bar" -> buildBarOption(option, colNames, rows, dimensionIndices.get(0), metricIndices, false);
            case "bar-horizontal" -> buildBarOption(option, colNames, rows, dimensionIndices.get(0), metricIndices, true);
            case "bar-grouped" -> buildGroupedBarOption(option, colNames, rows, dimensionIndices, metricIndices);
            case "line" -> buildLineOption(option, colNames, rows, timeDimensionIndices, metricIndices);
            default -> buildBarOption(option, colNames, rows, dimensionIndices.get(0), metricIndices, false);
        }

        return option;
    }

    private JSONObject buildTooltip(String chartType) {
        JSONObject tooltip = new JSONObject().set("trigger", "axis");
        if ("pie".equals(chartType)) {
            tooltip.set("trigger", "item");
            tooltip.set("formatter", "{b}: {c} ({d}%)");
        }
        return tooltip;
    }

    private JSONObject buildLegend(String chartType, List<Integer> metricIndices, List<String> colNames) {
        if ("pie".equals(chartType)) {
            return new JSONObject().set("orient", "vertical").set("left", "left");
        }
        if (metricIndices.size() <= 1) {
            return new JSONObject().set("show", false);
        }
        JSONArray data = new JSONArray();
        for (int idx : metricIndices) {
            data.put(colNames.get(idx));
        }
        return new JSONObject().set("data", data).set("top", "bottom");
    }

    /**
     * 饼图
     */
    private void buildPieOption(JSONObject option, List<String> colNames, JSONArray rows,
                                int dimIdx, int metricIdx) {
        String dimName = colNames.get(dimIdx);
        String metricName = colNames.get(metricIdx);

        JSONArray pieData = new JSONArray();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject item = new JSONObject();
            item.set("name", String.valueOf(row.get(dimName)));
            item.set("value", toDouble(row.get(metricName)));
            pieData.put(item);
        }

        JSONObject series = new JSONObject();
        series.set("name", metricName);
        series.set("type", "pie");
        series.set("radius", "50%");
        series.set("data", pieData);
        series.set("emphasis", new JSONObject()
                .set("itemStyle", new JSONObject()
                        .set("shadowBlur", 10)
                        .set("shadowOffsetX", 0)
                        .set("shadowColor", "rgba(0, 0, 0, 0.5)")));

        // 饼图不需要 xAxis/yAxis
        option.set("series", new JSONArray().put(series));
    }

    /**
     * 柱状图（垂直或水平）
     */
    private void buildBarOption(JSONObject option, List<String> colNames, JSONArray rows,
                                int dimIdx, List<Integer> metricIndices, boolean horizontal) {
        String dimName = colNames.get(dimIdx);

        // xAxis - 类目轴
        JSONArray categories = new JSONArray();
        for (int i = 0; i < rows.size(); i++) {
            categories.put(String.valueOf(rows.getJSONObject(i).get(dimName)));
        }

        JSONObject xAxis = new JSONObject().set("type", "category").set("data", categories);
        JSONObject yAxis = new JSONObject().set("type", "value");

        if (horizontal) {
            // 横向柱状图：交换 xAxis/yAxis
            xAxis.set("type", "value");
            yAxis.set("type", "category").set("data", categories);
        }

        option.set("xAxis", xAxis);
        option.set("yAxis", yAxis);

        // 构建 series
        JSONArray seriesArray = new JSONArray();
        for (int mIdx : metricIndices) {
            String metricName = colNames.get(mIdx);
            JSONObject series = new JSONObject();
            series.set("name", metricName);
            series.set("type", "bar");

            JSONArray values = new JSONArray();
            for (int i = 0; i < rows.size(); i++) {
                values.put(toDouble(rows.getJSONObject(i).get(metricName)));
            }
            series.set("data", values);
            seriesArray.put(series);
        }
        option.set("series", seriesArray);
    }

    /**
     * 分组柱状图（多维度）
     */
    private void buildGroupedBarOption(JSONObject option, List<String> colNames, JSONArray rows,
                                       List<Integer> dimensionIndices, List<Integer> metricIndices) {
        // 使用第一个维度作为 xAxis 类目
        int mainDimIdx = dimensionIndices.get(0);
        String mainDimName = colNames.get(mainDimIdx);

        // 构建组合类目（多个维度用 - 连接）
        JSONArray categories = new JSONArray();
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int d = 0; d < dimensionIndices.size(); d++) {
                if (d > 0) sb.append("-");
                sb.append(String.valueOf(rows.getJSONObject(i).get(colNames.get(dimensionIndices.get(d)))));
            }
            categories.put(sb.toString());
        }

        option.set("xAxis", new JSONObject().set("type", "category").set("data", categories));
        option.set("yAxis", new JSONObject().set("type", "value"));

        // 每个指标一个 series
        JSONArray seriesArray = new JSONArray();
        for (int mIdx : metricIndices) {
            String metricName = colNames.get(mIdx);
            JSONObject series = new JSONObject();
            series.set("name", metricName);
            series.set("type", "bar");

            JSONArray values = new JSONArray();
            for (int i = 0; i < rows.size(); i++) {
                values.put(toDouble(rows.getJSONObject(i).get(metricName)));
            }
            series.set("data", values);
            seriesArray.put(series);
        }
        option.set("series", seriesArray);
    }

    /**
     * 折线图（时间维度）
     */
    private void buildLineOption(JSONObject option, List<String> colNames, JSONArray rows,
                                 List<Integer> timeDimensionIndices, List<Integer> metricIndices) {
        // 使用第一个时间列作为 xAxis
        int timeIdx = timeDimensionIndices.get(0);
        String timeName = colNames.get(timeIdx);

        JSONArray timeData = new JSONArray();
        for (int i = 0; i < rows.size(); i++) {
            timeData.put(String.valueOf(rows.getJSONObject(i).get(timeName)));
        }

        option.set("xAxis", new JSONObject().set("type", "category").set("data", timeData).set("boundaryGap", false));
        option.set("yAxis", new JSONObject().set("type", "value"));

        JSONArray seriesArray = new JSONArray();
        for (int mIdx : metricIndices) {
            String metricName = colNames.get(mIdx);
            JSONObject series = new JSONObject();
            series.set("name", metricName);
            series.set("type", "line");
            series.set("smooth", true);

            JSONArray values = new JSONArray();
            for (int i = 0; i < rows.size(); i++) {
                values.put(toDouble(rows.getJSONObject(i).get(metricName)));
            }
            series.set("data", values);
            seriesArray.put(series);
        }
        option.set("series", seriesArray);
    }

    /**
     * 安全转换为 double
     */
    private double toDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 返回错误结果 JSON
     */
    private String errorResult(String message) {
        JSONObject result = new JSONObject();
        result.set("error", message);
        return result.toString();
    }
}
