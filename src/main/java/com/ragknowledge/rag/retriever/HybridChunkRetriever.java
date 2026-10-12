package com.ragknowledge.rag.retriever;

import com.ragknowledge.config.rag.RagRetrievalProperties;
import com.ragknowledge.document.entity.ChunkKeywordScore;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器，向量与关键词双通道召回并按 Reciprocal Rank Fusion 融合排序
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Component
public class HybridChunkRetriever {

    private final VectorStore vectorStore;
    private final DocumentChunkMapper documentChunkMapper;
    private final RagRetrievalProperties retrievalProperties;

    public HybridChunkRetriever(VectorStore vectorStore,
                                DocumentChunkMapper documentChunkMapper,
                                RagRetrievalProperties retrievalProperties) {
        this.vectorStore = vectorStore;
        this.documentChunkMapper = documentChunkMapper;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 在指定知识库内执行混合检索：向量语义通道与 pg_trgm 关键词通道分别召回候选，
     * 再按 Reciprocal Rank Fusion 返回有界候选池；调用方校验业务可见性后取最终 topK。
     * 混合检索关闭时退化为纯向量候选池。
     *
     * @param knowledgeBaseId     知识库 ID
     * @param query               检索文本
     * @param topK                目标返回数量，用于计算候选池大小
     * @param similarityThreshold 向量通道相似度阈值，不作用于关键词通道
     * @return 按分数降序的候选结果，最多两路候选池大小之和，可能超过 topK
     */
    public List<RetrievedChunk> retrieve(Long knowledgeBaseId, String query, int topK, double similarityThreshold) {
        // 每个通道召回超过 topK 的候选池，让融合阶段有互补空间。
        int poolSize = topK * retrievalProperties.getChannelPoolMultiplier();
        // 向量通道始终执行，语义召回是 RAG 检索的主通道。
        List<Document> vectorDocuments = searchVectorChannel(knowledgeBaseId, query, poolSize, similarityThreshold);

        // 纯向量模式也保留全部有界候选，失效片段不能提前占用最终名额。
        if (!retrievalProperties.isHybridEnabled()) {
            return toVectorOnlyResults(vectorDocuments);
        }

        // 关键词通道用 trigram 词相似度召回，补充精确术语、编号等字面匹配。
        List<ChunkKeywordScore> keywordScores = documentChunkMapper.selectKeywordScores(knowledgeBaseId, query, poolSize);
        // RRF 只排序不截断，业务校验之后由服务层选出最终 topK。
        return fuseByReciprocalRank(vectorDocuments, keywordScores);
    }

    /**
     * 执行向量通道检索。
     *
     * @param knowledgeBaseId     知识库 ID
     * @param query               检索文本
     * @param poolSize            召回数量
     * @param similarityThreshold 相似度阈值
     * @return 向量检索结果
     */
    private List<Document> searchVectorChannel(Long knowledgeBaseId, String query, int poolSize, double similarityThreshold) {
        // knowledgeBaseId 在向量 metadata 中保存为字符串，filterExpression 是知识库隔离的关键边界。
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(poolSize)
                .similarityThreshold(similarityThreshold)
                .filterExpression("knowledgeBaseId == '" + knowledgeBaseId + "'")
                .build();
        return vectorStore.similaritySearch(searchRequest);
    }

    /**
     * 将纯向量结果转换为检索结果，保留向量相似度作为分数。
     *
     * @param vectorDocuments 向量检索结果
     * @return 检索结果列表
     */
    private List<RetrievedChunk> toVectorOnlyResults(List<Document> vectorDocuments) {
        List<RetrievedChunk> results = new ArrayList<>();

        for (Document document : vectorDocuments) {
            Long chunkId = parseChunkId(document);

            // 缺少 chunkId 的向量记录无法建立业务引用，跳过。
            if (chunkId == null) {
                continue;
            }

            // 纯向量模式沿用向量相似度分数，保持与历史 API 行为一致。
            results.add(new RetrievedChunk(chunkId, document.getScore() == null ? 0.0 : document.getScore(), document));
        }

        return results;
    }

    /**
     * 按 Reciprocal Rank Fusion 融合向量和关键词通道结果。
     * RRF 只依赖排名不依赖分数量纲，天然适合融合余弦相似度和 trigram 相似度这类不可比的分数。
     *
     * @param vectorDocuments 向量通道结果（已按相似度降序）
     * @param keywordScores   关键词通道结果（已按词相似度降序）
     * @return 按融合分数降序的检索结果
     */
    private List<RetrievedChunk> fuseByReciprocalRank(List<Document> vectorDocuments,
                                                      List<ChunkKeywordScore> keywordScores) {
        int rrfK = retrievalProperties.getRrfK();
        // 融合分数按 chunkId 累加，两个通道都命中的 chunk 会获得更高分数。
        Map<Long, Double> fusedScores = new HashMap<>();
        // 保留向量文档引用，供引用来源展示 documentName 等 metadata。
        Map<Long, Document> vectorDocumentMap = new HashMap<>();

        // 向量通道贡献：1 / (rrfK + 排名)，排名从 1 开始。
        for (int rank = 0; rank < vectorDocuments.size(); rank++) {
            Document document = vectorDocuments.get(rank);
            Long chunkId = parseChunkId(document);

            // 缺少 chunkId 的向量记录不参与融合，无法追踪到业务 chunk。
            if (chunkId == null) {
                continue;
            }

            fusedScores.merge(chunkId, 1.0 / (rrfK + rank + 1), Double::sum);
            vectorDocumentMap.putIfAbsent(chunkId, document);
        }

        // 关键词通道贡献同样的排名倒数分数，两个通道权重相等。
        for (int rank = 0; rank < keywordScores.size(); rank++) {
            fusedScores.merge(keywordScores.get(rank).getId(), 1.0 / (rrfK + rank + 1), Double::sum);
        }

        // 按融合分数降序排序，分数相同时按 chunkId 保证排序稳定可复现。
        return fusedScores.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry<Long, Double>::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new RetrievedChunk(entry.getKey(), entry.getValue(), vectorDocumentMap.get(entry.getKey())))
                .toList();
    }

    /**
     * 从向量文档 metadata 中解析 chunkId。
     *
     * @param document 向量文档
     * @return chunkId，解析失败时返回 null
     */
    private Long parseChunkId(Document document) {
        Object value = document.getMetadata().get("chunkId");

        // metadata 缺少 chunkId 说明向量记录不符合当前系统规范，由调用方跳过。
        if (value == null) {
            return null;
        }

        try {
            // String.valueOf 兼容 String、Long、Integer 等简单 metadata 类型。
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            // metadata 异常不应中断检索，跳过该条向量更安全。
            return null;
        }
    }
}
