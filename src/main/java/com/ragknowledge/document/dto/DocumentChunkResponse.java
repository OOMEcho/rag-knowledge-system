package com.ragknowledge.document.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 文档分块响应
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "文档分块响应")
public record DocumentChunkResponse(
        // 雪花 ID 超出 JS Number 安全整数范围（2^53），序列化为字符串避免浏览器端精度丢失。
        @Schema(description = "分块 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "文档 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long documentId,

        @Schema(description = "知识库 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long knowledgeBaseId,

        @Schema(description = "分块序号")
        Integer chunkIndex,

        @Schema(description = "分块内容")
        String content,

        @Schema(description = "估算 token 数")
        Integer tokenCount,

        @Schema(description = "向量文档 ID")
        String vectorDocumentId,

        @Schema(description = "创建时间")
        LocalDateTime createdAt,

        @Schema(description = "更新时间")
        LocalDateTime updatedAt
) {
}
