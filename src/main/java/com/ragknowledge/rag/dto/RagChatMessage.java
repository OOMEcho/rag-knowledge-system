package com.ragknowledge.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 多轮对话历史消息
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Schema(description = "多轮对话历史消息")
public record RagChatMessage(
        @Schema(description = "消息角色", example = "USER")
        @NotNull(message = "历史消息角色不能为空")
        Role role,

        @Schema(description = "消息内容", example = "公司的报销流程是什么？")
        @NotBlank(message = "历史消息内容不能为空")
        String content
) {

    @Schema(description = "对话消息角色")
    public enum Role {
        USER,
        ASSISTANT
    }
}
