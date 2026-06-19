package com.chiho.wuagentscope.model;

import lombok.Data;

/**
 * POST 请求基类
 * <p>
 * 所有需要用户认证的 POST 请求 DTO 应继承此类，
 * 以统一携带 token 字段用于登录校验。
 *
 * @author ChiHo
 */
@Data
public class BaseRequest {

    /** 用户认证 token */
    private String token;
}
