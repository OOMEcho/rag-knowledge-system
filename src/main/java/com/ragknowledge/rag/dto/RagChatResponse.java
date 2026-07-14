package com.ragknowledge.rag.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * RAG 问答响应
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "RAG 问答响应")
public record RagChatResponse(
        // 雪花 ID 超出 JS Number 安全整数范围（2^53），序列化为字符串避免浏览器端精度丢失。
        @Schema(description = "知识库 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long knowledgeBaseId,

        @Schema(description = "用户问题")
        String question,

        @Schema(description = "多轮对话时用于检索的改写问题，未发生改写时为 null")
        String rewrittenQuestion,

        @Schema(description = "模型回答")
        String answer,

        @Schema(description = "引用来源列表")
        List<RagReferenceResponse> references
) {
}
