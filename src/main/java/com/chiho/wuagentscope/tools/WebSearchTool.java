package com.chiho.wuagentscope.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索工具 —— 基于 SearXNG 元搜索引擎
 * <p>
 * SearXNG 是一个自托管的元搜索引擎，聚合 Google、Bing、DuckDuckGo 等多个搜索引擎的结果。
 * 部署方式：Docker 一键部署，详见 docs/ 下的部署文档。
 *
 * @author Chiho
 */
@Component
@Slf4j
public class WebSearchTool {

    @Value("${searxng.base-url:http://localhost:8888}")
    private String baseUrl;

    @Value("${searxng.timeout:10000}")
    private int timeout;

    @Tool(name = "web_search", description = "联网搜索。当用户询问实时信息、最新新闻、天气、股价、赛事结果、科技动态、或任何你不确定的最新事实时，必须先获取当前最新的时间，然后调用此工具搜索再回答，严禁凭记忆编造。")
    public String search(
            @ToolParam(name = "query", description = "Search query keyword") String query,
            @ToolParam(name = "count", description = "Number of results to return, default 5, max 10") String count
    ) {
        log.info("##### ToolUse[WebSearchTool-web_search]: {}", query);
        if (StrUtil.isBlank(query)) {
            return "Error: query is required";
        }

        int num = parseCount(count);

        try {
            String url = baseUrl + "/search";
            HttpResponse response = HttpRequest.get(url)
                    .form("q", query)
                    .form("format", "json")
                    .form("pageno", 1)
                    .timeout(timeout)
                    .execute();

            if (response.getStatus() != HttpStatus.HTTP_OK) {
                log.info("SearXNG returned status {}: {}", response.getStatus(), response.body());
                return "Error: search service unavailable, status=" + response.getStatus();
            }

            return parseResults(response.body(), num);

        } catch (IllegalArgumentException e) {
            log.info("SearXNG URL 无效: {}", baseUrl, e);
            return "Error: invalid SearXNG base URL, please check searxng.base-url config";
        } catch (Exception e) {
            log.info("搜索失败: query={}", query, e);
            return "Error: search failed - " + e.getMessage();
        }
    }

    /**
     * 解析 SearXNG JSON 响应，提取 top-N 结果
     * <p>
     * SearXNG 返回格式：
     * {
     *   "results": [
     *     { "title": "...", "url": "...", "content": "snippet..." }
     *   ]
     * }
     */
    private String parseResults(String json, int count) {
        JSONObject root = JSONUtil.parseObj(json);
        JSONArray results = root.getJSONArray("results");

        if (results == null || results.isEmpty()) {
            return "No results found";
        }

        List<String> items = new ArrayList<>();
        int limit = Math.min(results.size(), count);

        for (int i = 0; i < limit; i++) {
            JSONObject item = results.getJSONObject(i);
            String title = item.getStr("title", "");
            String url = item.getStr("url", "");
            String content = item.getStr("content", "");

            if (StrUtil.isBlank(url)) {
                continue;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(i + 1).append(". ").append(title);
            sb.append("\n   URL: ").append(url);
            if (StrUtil.isNotBlank(content)) {
                sb.append("\n   摘要: ").append(content);
            }
            items.add(sb.toString());
        }

        if (items.isEmpty()) {
            return "No valid results found";
        }

        return String.join("\n\n", items);
    }

    /**
     * 解析返回结果数量，默认5，最大10
     */
    private int parseCount(String count) {
        if (StrUtil.isBlank(count)) {
            return 5;
        }
        try {
            int n = Integer.parseInt(count.trim());
            return Math.max(1, Math.min(n, 10));
        } catch (NumberFormatException e) {
            return 5;
        }
    }
}
