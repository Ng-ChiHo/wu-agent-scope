package com.chiho.wuagentscope.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 网页内容读取工具 —— 基于 Jina Reader API
 * <p>
 * Jina Reader 将任意网页转换为 LLM 友好的 Markdown 格式，自动去除导航栏、广告等噪音内容。
 * 免费使用，无需 API Key（有速率限制）。
 * <p>
 * 请求格式：GET https://r.jina.ai/{targetUrl}
 * 返回：Markdown 格式的网页正文内容
 *
 * @author Chiho
 */
@Component
@Slf4j
public class WebReaderTool {

    @Value("${jina.reader.base-url:https://r.jina.ai}")
    private String baseUrl;

    @Value("${jina.reader.timeout:30000}")
    private int timeout;

    @Value("${jina.reader.api-key:}")
    private String apiKey;

    @Value("${jina.reader.max-length:8000}")
    private int maxLength;

    @Tool(name = "web_read", description = "读取网页全文内容。当 web_search 搜索结果的摘要信息不够详细，或用户要求你阅读/总结某个具体网页链接时使用。")
    public String read(
            @ToolParam(name = "url", description = "The full URL of the web page to read, e.g. https://example.com/article") String url
    ) {
        log.info("##### ToolUse[WebReaderTool-web_read]: {}", url);
        if (StrUtil.isBlank(url)) {
            return "Error: url is required";
        }

        String trimmedUrl = url.trim();

        // 校验 URL 格式
        if (!isValidUrl(trimmedUrl)) {
            return "Error: invalid URL format, must start with http:// or https://";
        }

        try {
            String requestUrl = baseUrl + "/" + trimmedUrl;
            HttpRequest request = HttpRequest.get(requestUrl)
                    .timeout(timeout)
                    .header("Accept", "text/plain");

            // 可选的 API Key（免费额度更高）
            if (StrUtil.isNotBlank(apiKey)) {
                request.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse response = request.execute();

            if (response.getStatus() != HttpStatus.HTTP_OK) {
                log.info("Jina Reader 返回状态 {}: url={}", response.getStatus(), trimmedUrl);
                return "Error: failed to read page, status=" + response.getStatus();
            }

            String body = response.body();
            if (StrUtil.isBlank(body)) {
                return "Error: page content is empty";
            }

            // 截断过长内容，避免撑爆 LLM 上下文
            return truncate(body, maxLength);

        } catch (Exception e) {
            log.error("读取网页失败: url={}", trimmedUrl, e);
            return "Error: read page failed - " + e.getMessage();
        }
    }

    /**
     * 校验 URL 是否合法
     */
    private boolean isValidUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false;
        }
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * 截断文本到指定最大长度，在句子边界处截断以保持可读性
     */
    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }

        // 在最大长度附近找一个句子边界（句号、问号、感叹号、换行）
        int cutAt = maxLen;
        for (int i = maxLen - 1; i >= maxLen - 200 && i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '.' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n') {
                cutAt = i + 1;
                break;
            }
        }

        return text.substring(0, cutAt) + "\n\n[Content truncated, total length: " + text.length() + " chars]";
    }
}
