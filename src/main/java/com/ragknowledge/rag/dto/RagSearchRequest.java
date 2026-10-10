package com.ragknowledge.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * RAG 检索请求
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "RAG 检索请求")
public record RagSearchRequest(
        @Schema(description = "知识库 ID", example = "1")
        @NotNull(message = "知识库 ID 不能为空")
        @Min(value = 1, message = "知识库 ID 必须大于 0")
        Long knowledgeBaseId,

        @Schema(description = "检索问题", example = "公司的报销流程是什么？")
        @NotBlank(message = "问题不能为空")
        // 限制单次问题长度，防止超长输入占用模型与关键词查询资源。
        @Size(max = 4096, message = "问题最多 4096 个字符")
        String question,

        @Schema(description = "返回数量，不传使用默认值", example = "5")
        @Min(value = 1, message = "topK 必须大于 0")
        @Max(value = 50, message = "topK 不能超过 50")
        Integer topK,

        @Schema(description = "相似度阈值，范围 0 到 1，不传使用默认值", example = "0.65")
        @DecimalMin(value = "0.0", message = "相似度阈值不能小于 0")
        @DecimalMax(value = "1.0", message = "相似度阈值不能大于 1")
        Double similarityThreshold
) {
}
