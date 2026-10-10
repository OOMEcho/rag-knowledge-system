package com.ragknowledge.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.config.rag.RagRetrievalProperties;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import com.ragknowledge.rag.dto.*;
import com.ragknowledge.rag.retriever.HybridChunkRetriever;
import com.ragknowledge.rag.retriever.RetrievedChunk;
import com.ragknowledge.rag.service.impl.RagServiceImpl;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 验证候选片段回查与输入边界，防止未完成资料和超长历史进入问答。
 *
 * @author xuesong.lei
 * @since 2026-10-10
 */
class RagBoundaryTests {
    @Test
    void invalidCandidatesShouldNotCallAnswerModel() {
        var bases = mock(KnowledgeBaseMapper.class);
        var chunks = mock(DocumentChunkMapper.class);
        var retriever = mock(HybridChunkRetriever.class);
        var model = mock(ChatModel.class);
        var base = new KnowledgeBaseEntity();
        // 模拟向量召回残留片段，业务查询过滤后没有可用引用。
        when(bases.selectOne(any())).thenReturn(base);
        when(retriever.retrieve(7L, "policy", 5, 0.65)).thenReturn(List.of(new RetrievedChunk(1L, 0.1, null)));
        when(chunks.selectRetrievableChunks(7L, List.of(1L))).thenReturn(List.of());
        var service = new RagServiceImpl(model, retriever, bases, chunks, new RagRetrievalProperties(), new ObjectMapper());
        var response = service.chat(new RagChatRequest(7L, "policy", null, null, null));
        assertThat(response.references()).isEmpty();
        assertThat(response.answer()).contains("无法基于已有知识回答");
        verify(chunks).selectRetrievableChunks(7L, List.of(1L));
        // ChatClient 构建会读取模型默认配置，拒答只要求不执行实际生成。
        verify(model, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(model, never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void oversizedQuestionsHistoryAndNullMessagesShouldBeRejected() {
        // 使用真实 Bean Validation，覆盖 API 接收的请求模型。
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new RagSearchRequest(7L, "x".repeat(4097), null, null))).isNotEmpty();
            assertThat(validator.validate(new RagChatRequest(7L, "x".repeat(4097), null, null, null))).isNotEmpty();
            var oversized = new RagChatMessage(RagChatMessage.Role.USER, "x".repeat(8193));
            assertThat(validator.validate(new RagChatRequest(7L, "policy", List.of(oversized), null, null))).isNotEmpty();
            assertThat(validator.validate(new RagChatRequest(7L, "policy", Arrays.asList((RagChatMessage) null), null, null))).isNotEmpty();
        }
    }
}
