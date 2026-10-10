package com.ragknowledge.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识库 Mapper
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

    /**
     * 锁定未删除知识库，在调用方事务内串行化上传与知识库删除。
     *
     * @param id 知识库 ID
     * @return 未删除知识库，不存在时返回 null
     */
    @Select("SELECT * FROM knowledge_base WHERE id = #{id} AND deleted = FALSE FOR UPDATE")
    KnowledgeBaseEntity selectActiveForUpdate(@Param("id") Long id);
}
