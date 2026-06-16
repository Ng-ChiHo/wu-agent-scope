package com.chiho.wuagentscope.controller;

import com.chiho.wuagentscope.common.R;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.service.MetabaseEmbedService;
import com.chiho.wuagentscope.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表看板控制器
 * <p>
 * 提供 Metabase 嵌入 URL 生成接口，前端获取 URL 后通过 iframe 嵌入看板。
 *
 * @author ChiHo
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Resource
    private MetabaseEmbedService metabaseEmbedService;

    @Resource
    private UserService userService;

    /**
     * 获取 Metabase 看板嵌入 URL
     * <p>
     * 返回的 URL 带有 JWT 签名，可直接放入 iframe src。
     * URL 中的 token 有效期为 10 分钟，过期后需重新获取。
     * <p>
     * 前端使用示例：
     * <pre>
     * &lt;iframe src="${embedUrl}" frameborder="0" width="100%" height="800px"&gt;&lt;/iframe&gt;
     * </pre>
     *
     * @param token 用户登录 token
     * @return Metabase 嵌入 URL
     */
    @GetMapping("/embed")
    public R<String> getEmbedUrl(@RequestParam String token) {
        Long userId = validateToken(token);
        String embedUrl = metabaseEmbedService.generateEmbedUrl(userId);
        return R.success(embedUrl);
    }

    /**
     * 验证 token 并返回用户 ID
     */
    private Long validateToken(String token) {
        Long userId = userService.getUserIdByToken(token);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_TOKEN);
        }
        return userId;
    }
}
