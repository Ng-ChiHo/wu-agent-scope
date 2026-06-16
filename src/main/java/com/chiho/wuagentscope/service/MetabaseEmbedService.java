package com.chiho.wuagentscope.service;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Metabase Signed Embedding 服务
 * <p>
 * 负责签发 JWT token 并生成 Metabase Dashboard 嵌入 URL。
 * JWT payload 包含 Dashboard ID 和用户过滤参数（user_id），
 * Metabase 验证签名后按参数过滤展示数据。
 *
 * @author ChiHo
 */
@Service
@Slf4j
public class MetabaseEmbedService {

    @Value("${metabase.site-url}")
    private String metabaseSiteUrl;

    @Value("${metabase.embedding-secret-key}")
    private String embeddingSecretKey;

    @Value("${metabase.dashboard-id}")
    private Integer dashboardId;

    /** JWT 过期时间（10 分钟） */
    private static final long TOKEN_EXPIRY_MS = 12 * 60 * 60 * 1000L;

    /**
     * 生成 Metabase Signed Embedding URL
     * <p>
     * JWT payload 结构：
     * <pre>
     * {
     *   "resource": {"dashboard": &lt;dashboardId&gt;},
     *   "params": {"user_id": "&lt;userId&gt;"},
     *   "exp": &lt;过期时间戳&gt;
     * }
     * </pre>
     *
     * @param userId 当前登录用户 ID，用于 Dashboard SQL 中的 [[AND user_id = {{user_id}}]] 过滤
     * @return 完整的 Metabase 嵌入 URL（可直接放入 iframe src）
     */
    public String generateEmbedUrl(Long userId) {
        // 构建 JWT payload
        Map<String, Object> resource = new HashMap<>();
        resource.put("dashboard", dashboardId);

        Map<String, Object> params = new HashMap<>();
        params.put("user_id", String.valueOf(userId));

        Map<String, Object> payload = new HashMap<>();
        payload.put("resource", resource);
        payload.put("params", params);
        payload.put("exp", new Date(System.currentTimeMillis() + TOKEN_EXPIRY_MS));

        // 签发 JWT（HS256）
        SecretKey key = new SecretKeySpec(
                embeddingSecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        String token = Jwts.builder()
                .claims(payload)
                .signWith(key)
                .compact();

        // 拼接嵌入 URL
        // #bordered=false 去掉边框, #titled=false 去掉标题栏, #theme=transparent 透明主题
        String embedUrl = metabaseSiteUrl + "/embed/dashboard/" + token
                + "#bordered=false&titled=false&theme=transparent";

        log.debug("生成 Metabase 嵌入 URL: userId={}, dashboardId={}", userId, dashboardId);
        return embedUrl;
    }
}
