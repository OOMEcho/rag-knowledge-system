package com.ragknowledge.rag.service;

import com.ragknowledge.rag.dto.RagChatRequest;
import com.ragknowledge.rag.dto.RagChatResponse;
import com.ragknowledge.rag.dto.RagSearchRequest;
import com.ragknowledge.rag.dto.RagSearchResponse;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * RAG 服务接口：检索、问答与流式问答
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public interface RagService {

    /**
     * 在指定知识库内执行混合检索（向量 + 关键词 RRF 融合），并返回可追踪的引用来源。
     *
     * @param request RAG 检索请求，包含知识库 ID、问题、topK 和相似度阈值
     * @return RAG 检索响应，包含检索参数和引用来源列表
     * @throws com.ragknowledge.common.exception.BusinessException 当知识库不存在或请求参数不符合业务规则时抛出
     */
    RagSearchResponse search(RagSearchRequest request);

    /**
     * 在指定知识库内执行 RAG 问答，并返回模型回答和引用来源。
     * 携带对话历史时会先通过大模型把追问改写为独立检索问题（改写失败自动回退原始问题）。
     *
     * @param request RAG 问答请求，包含知识库 ID、问题、可选对话历史、topK 和相似度阈值
     * @return RAG 问答响应，包含回答文本、可选改写问题和引用来源列表
     * @throws com.ragknowledge.common.exception.BusinessException 当知识库不存在或请求参数不符合业务规则时抛出
     */
    RagChatResponse chat(RagChatRequest request);

    /**
     * 以 SSE 流式执行 RAG 问答。事件顺序：可选 rewrittenQuestion 事件、references 事件（JSON 引用列表）、
     * 若干 delta 事件（回答增量文本）、done 事件；任何阶段失败发出 error 事件后结束流。
     *
     * @param request RAG 问答请求，参数语义与 {@link #chat(RagChatRequest)} 一致
     * @return SSE 事件流，异常已在流内转换为 error 事件，不会向调用方抛出
     */
    Flux<ServerSentEvent<String>> chatStream(RagChatRequest request);
}
