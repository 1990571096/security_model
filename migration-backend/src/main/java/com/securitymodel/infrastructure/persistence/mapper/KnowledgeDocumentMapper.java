package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.application.knowledge.KnowledgeSearchRow;
import com.securitymodel.domain.knowledge.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    @Select("""
            SELECT * FROM sys_knowledge_document
            WHERE source_key = #{sourceKey} AND status = 'active'
            ORDER BY version DESC LIMIT 1
            """)
    KnowledgeDocument findActiveBySourceKey(@Param("sourceKey") String sourceKey);

    @Select("SELECT COUNT(*) FROM sys_knowledge_document WHERE status = 'active'")
    long countActive();

    @Update("""
            UPDATE sys_knowledge_document
            SET status = 'retired', updated_at = #{now}
            WHERE source_key = #{sourceKey} AND status = 'active'
              AND document_id <> #{keepDocumentId}
            """)
    int retireOtherVersions(@Param("sourceKey") String sourceKey,
                            @Param("keepDocumentId") Long keepDocumentId,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_knowledge_document
            SET status = 'retired', updated_at = #{now}
            WHERE document_id = #{documentId} AND status = 'active'
            """)
    int retireById(@Param("documentId") Long documentId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT c.chunk_id, c.document_id, c.chunk_index, c.section_path, c.content,
                   c.start_line, c.end_line, d.title, d.source_key, d.source_type,
                   d.version, d.visibility, d.owner_user_id
            FROM sys_knowledge_chunk c
            JOIN sys_knowledge_document d ON d.document_id = c.document_id
            WHERE d.status = 'active'
              AND (d.visibility = 'shared' OR d.owner_user_id = #{ownerUserId})
            ORDER BY d.document_id, c.chunk_index
            LIMIT #{limit}
            """)
    List<KnowledgeSearchRow> findSearchableForUser(@Param("ownerUserId") Long ownerUserId,
                                                   @Param("limit") int limit);

    @Select("""
            SELECT c.chunk_id, c.document_id, c.chunk_index, c.section_path, c.content,
                   c.start_line, c.end_line, d.title, d.source_key, d.source_type,
                   d.version, d.visibility, d.owner_user_id
            FROM sys_knowledge_chunk c
            JOIN sys_knowledge_document d ON d.document_id = c.document_id
            WHERE d.status = 'active'
            ORDER BY d.document_id, c.chunk_index
            LIMIT #{limit}
            """)
    List<KnowledgeSearchRow> findSearchableAll(@Param("limit") int limit);
}
