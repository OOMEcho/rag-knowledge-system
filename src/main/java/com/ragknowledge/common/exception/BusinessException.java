package com.ragknowledge.common.exception;

import com.ragknowledge.common.response.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码与业务错误码，由全局异常处理器统一转为标准响应
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    public BusinessException(ErrorCode errorCode, String message) {
        // 默认按 400 处理业务异常，后续具体业务可通过重载构造函数表达 404 或 409 等更精确状态。
        this(errorCode, message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(ErrorCode errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }
}
