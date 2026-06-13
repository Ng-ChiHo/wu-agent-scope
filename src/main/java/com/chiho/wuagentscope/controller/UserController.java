package com.chiho.wuagentscope.controller;

import com.chiho.wuagentscope.common.R;
import com.chiho.wuagentscope.model.LoginRequest;
import com.chiho.wuagentscope.model.LoginResponse;
import com.chiho.wuagentscope.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理控制器
 * <p>
 * 提供用户注册、登录、登出、Token 验证接口。
 * @author ChiHo
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户注册
     *
     * @param username 用户名（必填）
     * @param password 密码（必填）
     * @param nickname 昵称（可选，默认等于用户名）
     */
    @PostMapping("/register")
    public R<String> register(@RequestParam String username,
                              @RequestParam String password,
                              @RequestParam(required = false) String nickname) {
        userService.register(username, password, nickname);
        return R.success("注册成功");
    }

    /**
     * 用户登录
     *
     * @param request 登录请求体（username + password）
     * @return 登录响应（userId, username, nickname, token）
     */
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        return R.success(response);
    }

    /**
     * 用户登出
     *
     * @param token 登录 token
     */
    @PostMapping("/logout")
    public R<String> logout(@RequestParam String token) {
        userService.logout(token);
        return R.success("登出成功");
    }

    /**
     * 验证 token 是否有效
     *
     * @param token 登录 token
     * @return true=有效，false=无效
     */
    @GetMapping("/validate")
    public R<Boolean> validateToken(@RequestParam String token) {
        Long userId = userService.getUserIdByToken(token);
        return R.success(userId != null);
    }
}
