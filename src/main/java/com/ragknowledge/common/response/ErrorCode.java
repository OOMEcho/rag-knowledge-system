package com.ragknowledge.common.response;

/**
 * 业务错误码枚举，作为对外协议的稳定错误标识
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public enum ErrorCode {

    VALIDATION_FAILED("VALIDATION_FAILED", "请求参数校验失败"),
    BUSINESS_ERROR("BUSINESS_ERROR", "业务处理失败"),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "资源不存在"),
    RESOURCE_CONFLICT("RESOURCE_CONFLICT", "资源状态冲突"),
    UNAUTHORIZED("UNAUTHORIZED", "未认证或认证失败"),
    RATE_LIMITED("RATE_LIMITED", "请求频率超过限制"),
    INTERNAL_ERROR("INTERNAL_ERROR", "系统内部错误");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
