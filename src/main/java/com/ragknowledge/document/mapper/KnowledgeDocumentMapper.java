package com.ragknowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识文档 Mapper
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    /**
     * 锁定未删除文档，在删除事务中阻止重复删除和并发入库抢占。
     *
     * @param id 文档 ID
     * @return 未删除文档，不存在时返回 null
     */
    @Select("SELECT * FROM knowledge_document WHERE id = #{id} AND deleted = FALSE FOR UPDATE")
    KnowledgeDocumentEntity selectActiveForUpdate(@Param("id") Long id);
}
