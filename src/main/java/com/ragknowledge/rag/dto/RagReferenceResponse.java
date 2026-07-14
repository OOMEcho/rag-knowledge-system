package com.ragknowledge.rag.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * RAG 引用来源
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "RAG 引用来源")
public record RagReferenceResponse(
        // 雪花 ID 超出 JS Number 安全整数范围（2^53），序列化为字符串避免浏览器端精度丢失。
        @Schema(description = "chunk ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long chunkId,

        @Schema(description = "文档 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long documentId,

        @Schema(description = "知识库 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long knowledgeBaseId,

        @Schema(description = "文档名称")
        String documentName,

        @Schema(description = "chunk 序号")
        Integer chunkIndex,

        @Schema(description = "来源类型")
        String sourceType,

        @Schema(description = "检索排序分数：混合检索时为 RRF 融合分数，纯向量检索时为余弦相似度")
        Double score,

        @Schema(description = "引用内容")
        String content
) {
}
