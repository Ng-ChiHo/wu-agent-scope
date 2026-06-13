package com.chiho.wuagentscope.common;

import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 统一返回结果封装
 * @author ChiHo
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {

    private Integer code;
    private String message;
    private T data;

    public R() {
    }

    public R(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> success(T data) {
        return new R<>(0, "success", data);
    }

    public static R<Void> success() {
        return new R<>(0, "success", null);
    }

    public static <T> R<T> fail(Integer code, String message) {
        return new R<>(code, message, null);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMessage(), null);
    }
}
