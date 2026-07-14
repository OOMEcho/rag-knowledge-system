package com.ragknowledge.rag.controller;

import com.ragknowledge.common.response.ApiResponse;
import com.ragknowledge.rag.dto.RagChatRequest;
import com.ragknowledge.rag.dto.RagChatResponse;
import com.ragknowledge.rag.dto.RagSearchRequest;
import com.ragknowledge.rag.dto.RagSearchResponse;
import com.ragknowledge.rag.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RAG 接口：混合检索、问答与 SSE 流式问答
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@RestController
@RequestMapping("/api/v1/rag")
@Tag(name = "RAG", description = "RAG 检索与问答 API")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/search")
    @Operation(summary = "RAG 向量检索")
    public ApiResponse<RagSearchResponse> search(@Valid @RequestBody RagSearchRequest request) {
        // 调用 RAG 服务执行知识库隔离检索，服务层会校验知识库并过滤非活跃 chunk。
        RagSearchResponse response = ragService.search(request);
        // 统一响应封装检索结果，便于调用方调试引用来源和检索参数。
        return ApiResponse.success(response);
    }

    @PostMapping("/chat")
    @Operation(summary = "RAG 问答", description = "支持通过 history 传入多轮对话历史，服务端会自动改写追问后检索")
    public ApiResponse<RagChatResponse> chat(@Valid @RequestBody RagChatRequest request) {
        // 调用 RAG 服务执行检索增强问答，确保答案和引用来源来自同一批检索结果。
        RagChatResponse response = ragService.chat(request);
        // 统一响应封装问答结果，保持 API 协议一致。
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "RAG 流式问答", description = "SSE 事件顺序：rewrittenQuestion（可选）、references（JSON 引用列表）、delta（回答增量）、done；失败时发出 error 事件")
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody RagChatRequest request) {
        // SSE 流式返回，引用来源先下发，回答文本按模型生成进度逐段推送。
        return ragService.chatStream(request);
    }
}
