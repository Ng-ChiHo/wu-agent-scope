package com.chiho.wuagentscope.common.exception;

import lombok.Getter;

/**
 * 错误码枚举
 * @author ChiHo
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),

    // 业务错误码 (400xx)
    INVALID_PARAM(40001, "请求参数错误"),
    PARAM_BIND_ERROR(40002, "请求参数绑定失败"),
    REQUEST_BODY_FORMAT_ERROR(40003, "请求体格式错误"),
    CONVERSATION_ID_NOT_FOUND(40011, "会话不存在或无权访问"),
    INVALID_LOGIN_TOKEN(40012, "无效的token，请先登录"),
    USER_NAME_UNIQUE(40013, "用户名已存在"),
    USER_QUERY_NOT_FOUND(40014, "用户不存在"),
    USER_INVALID_STATUS(40015, "用户已被禁用"),
    PASSWORD_ERROR(40016, "密码错误"),
    MODEL_NOT_FOUND(40020, "不支持的模型"),

    // 系统错误码 (500xx)
    SYSTEM_ERROR(50000, "系统异常"),
    AGENT_RUN_FAILED(50010, "Agent执行失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
