package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.knowledge.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    @Select("""
            SELECT * FROM sys_knowledge_chunk
            WHERE document_id = #{documentId}
            ORDER BY chunk_index
            """)
    List<KnowledgeChunk> findByDocumentId(@Param("documentId") Long documentId);
}
