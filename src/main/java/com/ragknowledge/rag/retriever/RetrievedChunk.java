package com.ragknowledge.rag.retriever;

import org.springframework.ai.document.Document;

/**
 * 混合检索结果：chunk ID、融合分数和可选的向量文档。
 * 仅被关键词通道召回的 chunk 没有向量文档，引用元数据需回落到 chunk 表的 metadataJson。
 *
 * @param chunkId        chunk ID
 * @param score          RRF 融合分数，仅用于结果排序和展示，不等价于余弦相似度
 * @param vectorDocument 向量通道召回的 Spring AI 文档，关键词通道独有结果为 null
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public record RetrievedChunk(Long chunkId, double score, Document vectorDocument) {
}
