package com.ragknowledge.document.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 知识文档响应
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "知识文档响应")
public record KnowledgeDocumentResponse(
        // 雪花 ID 超出 JS Number 安全整数范围（2^53），序列化为字符串避免浏览器端精度丢失。
        @Schema(description = "文档 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "所属知识库 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long knowledgeBaseId,

        @Schema(description = "清洗后的文件名")
        String fileName,

        @Schema(description = "客户端原始文件名")
        String originalFileName,

        @Schema(description = "文件类型")
        String contentType,

        @Schema(description = "文件大小")
        Long fileSize,

        @Schema(description = "SHA-256 校验和")
        String checksumSha256,

        @Schema(description = "文档状态")
        String status,

        @Schema(description = "失败原因")
        String failureReason,

        @Schema(description = "分块数量")
        Integer chunkCount,

        @Schema(description = "创建时间")
        LocalDateTime createdAt,

        @Schema(description = "更新时间")
        LocalDateTime updatedAt
) {
}
