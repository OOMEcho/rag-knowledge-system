package com.ragknowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragknowledge.document.entity.ChunkKeywordScore;
import com.ragknowledge.document.entity.DocumentChunkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档分块 Mapper，含关键词通道打分查询
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {

    /**
     * 在指定知识库内按 pg_trgm 词相似度检索活跃 chunk，作为混合检索的关键词通道。
     * word_similarity 衡量查询串与 chunk 内容中最相似片段的重合度，对中英文关键词都有效，
     * 弥补向量检索对精确术语、编号等字面匹配不敏感的短板。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           检索文本
     * @param limit           返回数量上限
     * @return 按词相似度降序的 chunk ID 与分数列表，分数为 0 的无关结果已过滤
     */
    @Select("SELECT id, word_similarity(#{query}, content) AS score "
            + "FROM document_chunk "
            + "WHERE knowledge_base_id = #{knowledgeBaseId} AND deleted = FALSE "
            + "AND word_similarity(#{query}, content) > 0 "
            + "ORDER BY score DESC, id ASC "
            + "LIMIT #{limit}")
    List<ChunkKeywordScore> selectKeywordScores(@Param("knowledgeBaseId") Long knowledgeBaseId,
                                                @Param("query") String query,
                                                @Param("limit") int limit);
}
