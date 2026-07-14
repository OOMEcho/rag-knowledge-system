package com.ragknowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragknowledge.document.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档 Mapper
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
}
