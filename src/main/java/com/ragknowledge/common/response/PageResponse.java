package com.ragknowledge.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一分页响应结构
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "分页响应结构")
public record PageResponse<T>(
        @Schema(description = "当前页数据")
        List<T> records,

        @Schema(description = "总记录数")
        long total,

        @Schema(description = "当前页码，从 1 开始")
        long pageNum,

        @Schema(description = "每页大小")
        long pageSize
) {

    /**
     * 构造分页响应，统一封装分页元数据和当前页记录。
     *
     * @param records  当前页记录
     * @param total    总记录数
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @param <T>      当前页记录类型
     * @return 统一分页响应
     */
    public static <T> PageResponse<T> of(List<T> records, long total, long pageNum, long pageSize) {
        // records 由服务层按业务 DTO 构造，这里只统一分页协议，避免 Controller 重复拼装分页字段。
        return new PageResponse<>(records, total, pageNum, pageSize);
    }
}
