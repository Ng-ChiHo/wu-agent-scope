package com.chiho.wuagentscope.common.exception;

import com.chiho.wuagentscope.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 全局异常处理器
 * 统一包装异常响应格式，避免泄漏内部堆栈信息
 * @author ChiHo
 */
@RestControllerAdvice(basePackages = "com.chiho.wuagentscope.controller")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验异常（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : ErrorCode.INVALID_PARAM.getMessage();
        return R.fail(ErrorCode.INVALID_PARAM.getCode(), message);
    }

    /** 参数绑定异常 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : ErrorCode.PARAM_BIND_ERROR.getMessage();
        return R.fail(ErrorCode.PARAM_BIND_ERROR.getCode(), message);
    }

    /** 请求体格式异常 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return R.fail(ErrorCode.REQUEST_BODY_FORMAT_ERROR);
    }

    /** SSE 客户端断开（浏览器关闭、前端主动 close）属于预期行为，不包装成 JSON */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        log.info("SSE client disconnected: {}", e.getMessage());
    }

    /** 未捕获异常 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }
}
