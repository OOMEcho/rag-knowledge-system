package com.ragknowledge.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 知识库响应
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "知识库响应")
public record KnowledgeBaseResponse(
        // 雪花 ID 超出 JS Number 安全整数范围（2^53），序列化为字符串避免浏览器端精度丢失。
        @Schema(description = "知识库 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "知识库名称")
        String name,

        @Schema(description = "知识库描述")
        String description,

        @Schema(description = "知识库状态")
        String status,

        @Schema(description = "文档数量")
        Integer documentCount,

        @Schema(description = "创建时间")
        LocalDateTime createdAt,

        @Schema(description = "更新时间")
        LocalDateTime updatedAt
) {
}
