package com.ragknowledge.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.config.rag.RagRetrievalProperties;
import com.ragknowledge.document.entity.DocumentChunkEntity;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import com.ragknowledge.rag.dto.RagSearchRequest;
import com.ragknowledge.rag.retriever.HybridChunkRetriever;
import com.ragknowledge.rag.service.impl.RagServiceImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证真实检索器与服务层协作，失效候选不能占用最终引用名额。
 *
 * @author xuesong.lei
 * @since 2026-10-12
 */
class RagCandidateSelectionTests {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void validCandidatesBeyondTopKShouldFillReferences(boolean hybridEnabled) {
        var vectorStore = mock(VectorStore.class);
        var chunks = mock(DocumentChunkMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var properties = new RagRetrievalProperties();
        properties.setHybridEnabled(hybridEnabled);
        // topK=2 时候选池为 4；前两条失效，只有第三、第四条通过业务校验。
        var candidates = LongStream.rangeClosed(1, 4)
                .mapToObj(id -> new Document(String.valueOf(id), "policy", Map.of("chunkId", String.valueOf(id))))
                .toList();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            // 扩大候选池仍必须保持知识库过滤，不通过扩大搜索范围补足数量。
            assertThat(request.getTopK()).isEqualTo(4);
            assertThat(request.getFilterExpression()).isNotNull();
            return candidates;
        });
        when(chunks.selectKeywordScores(7L, "policy", 4)).thenReturn(List.of());
        when(bases.selectOne(any())).thenReturn(new KnowledgeBaseEntity());
        when(chunks.selectRetrievableChunks(7L, List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of(chunk(3L), chunk(4L)));
        // 使用真实融合与最终选择，仅替换数据库和模型基础设施。
        var retriever = new HybridChunkRetriever(vectorStore, chunks, properties);
        var service = new RagServiceImpl(mock(ChatModel.class), retriever, bases, chunks, properties, new ObjectMapper());
        var response = service.search(new RagSearchRequest(7L, "policy", 2, 0.65));
        assertThat(response.references()).extracting(reference -> reference.chunkId()).containsExactly(3L, 4L);
        // 全部候选有效时仍只返回目标数量，保留候选池不能扩大公开 API 的返回数。
        when(chunks.selectRetrievableChunks(7L, List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of(chunk(1L), chunk(2L), chunk(3L), chunk(4L)));
        assertThat(service.search(new RagSearchRequest(7L, "policy", 2, 0.65)).references())
                .extracting(reference -> reference.chunkId()).containsExactly(1L, 2L);
    }

    /** 创建可引用业务片段，引用正文必须来自此实体而非失效的向量内容。 */
    private DocumentChunkEntity chunk(long id) {
        var chunk = new DocumentChunkEntity();
        chunk.setId(id);
        chunk.setDocumentId(70L);
        chunk.setKnowledgeBaseId(7L);
        chunk.setChunkIndex((int) id);
        chunk.setContent("active policy");
        return chunk;
    }
}
