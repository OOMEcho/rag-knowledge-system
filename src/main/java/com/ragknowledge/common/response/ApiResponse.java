package com.ragknowledge.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 统一 API 响应结构
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "统一 API 响应结构")
public record ApiResponse<T>(
        @Schema(description = "请求是否成功")
        boolean success,

        @Schema(description = "业务响应码")
        String code,

        @Schema(description = "响应消息")
        String message,

        @Schema(description = "响应数据")
        T data,

        @Schema(description = "服务端响应时间")
        OffsetDateTime timestamp
) {

    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String SUCCESS_MESSAGE = "success";

    /**
     * 构造成功响应，统一封装业务数据和服务端时间。
     *
     * @param data 业务响应数据
     * @param <T>  业务响应数据类型
     * @return 统一成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        // 成功响应固定使用稳定协议码，避免不同接口自行定义成功码导致客户端适配成本增加。
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, data, OffsetDateTime.now());
    }

    /**
     * 构造失败响应，统一封装错误码、错误消息和服务端时间。
     *
     * @param errorCode 业务错误码
     * @param message   对外错误消息
     * @return 统一失败响应
     */
    public static ApiResponse<Void> failure(ErrorCode errorCode, String message) {
        // 错误响应使用枚举错误码作为稳定协议字段，避免向客户端暴露异常类名或内部实现细节。
        return new ApiResponse<>(false, errorCode.code(), message, null, OffsetDateTime.now());
    }
}
