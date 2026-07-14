package com.ragknowledge.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "创建知识库请求")
public record CreateKnowledgeBaseRequest(
        @Schema(description = "知识库名称", example = "企业制度知识库")
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "知识库名称不能超过 128 个字符")
        String name,

        @Schema(description = "知识库描述", example = "用于存放企业制度、流程和规范文档")
        @Size(max = 2000, message = "知识库描述不能超过 2000 个字符")
        String description
) {
}
