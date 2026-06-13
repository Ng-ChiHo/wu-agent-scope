package com.chiho.wuagentscope.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 登录token */
    private String token;
}
