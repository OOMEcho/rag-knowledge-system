package com.ragknowledge.rag.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * RAG 检索响应
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "RAG 检索响应")
public record RagSearchResponse(
        // 雪花 ID 超出 JS Number 安全整数范围（2^53），序列化为字符串避免浏览器端精度丢失。
        @Schema(description = "知识库 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long knowledgeBaseId,

        @Schema(description = "检索问题")
        String question,

        @Schema(description = "实际 topK")
        Integer topK,

        @Schema(description = "实际相似度阈值")
        Double similarityThreshold,

        @Schema(description = "引用来源列表")
        List<RagReferenceResponse> references
) {
}
