package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.application.knowledge.KnowledgeDocumentSummary;
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

    String VISIBLE_DOCUMENTS = """
            FROM sys_knowledge_document
            WHERE status = 'active'
              AND source_type <> 'sdd'
              AND (#{allVisible} = TRUE OR visibility = 'shared' OR owner_user_id = #{ownerUserId})
            """;

    @Select("""
            SELECT * FROM sys_knowledge_document
            WHERE source_key = #{sourceKey} AND status = 'active'
            ORDER BY version DESC LIMIT 1
            """)
    KnowledgeDocument findActiveBySourceKey(@Param("sourceKey") String sourceKey);

    @Select("""
            SELECT document_id, source_key, source_type FROM sys_knowledge_document
            WHERE status = 'active' AND source_type IN ('sdd', 'knowledge')
            """)
    List<KnowledgeDocument> findActiveFileDocuments();

    @Select("SELECT COUNT(*) FROM sys_knowledge_document WHERE status = 'active' AND source_type <> 'sdd'")
    long countActive();

    @Select("SELECT COUNT(*) " + VISIBLE_DOCUMENTS)
    long countVisible(@Param("ownerUserId") Long ownerUserId, @Param("allVisible") boolean allVisible);

    @Select("""
            SELECT title, source_key AS sourceKey, source_type AS sourceType, visibility,
                   version, updated_at AS updatedAt
            """ + VISIBLE_DOCUMENTS + """
            ORDER BY source_type, title, document_id
            LIMIT #{size} OFFSET #{offset}
            """)
    List<KnowledgeDocumentSummary> findVisible(@Param("ownerUserId") Long ownerUserId,
                                               @Param("allVisible") boolean allVisible,
                                               @Param("size") int size, @Param("offset") long offset);

    @Select("""
            SELECT * FROM sys_knowledge_document
            WHERE status = 'retired'
              AND vector_cleanup_status IN ('pending', 'failed')
              AND (vector_cleanup_next_at IS NULL OR vector_cleanup_next_at <= #{now})
            ORDER BY COALESCE(vector_cleanup_next_at, updated_at) ASC, document_id ASC
            LIMIT #{limit}
            """)
    List<KnowledgeDocument> findRetiredForVectorCleanup(@Param("now") LocalDateTime now,
                                                        @Param("limit") int limit);

    @Update("""
            UPDATE sys_knowledge_document
            SET vector_cleanup_status = 'clean',
                vector_cleanup_next_at = NULL,
                vector_cleanup_updated_at = #{now}
            WHERE document_id = #{documentId} AND status = 'retired'
            """)
    int markVectorCleanupClean(@Param("documentId") Long documentId,
                               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_knowledge_document
            SET vector_cleanup_status = 'failed',
                vector_cleanup_attempts = vector_cleanup_attempts + 1,
                vector_cleanup_next_at = #{nextAt},
                vector_cleanup_updated_at = #{now}
            WHERE document_id = #{documentId} AND status = 'retired'
            """)
    int markVectorCleanupFailed(@Param("documentId") Long documentId,
                                @Param("now") LocalDateTime now,
                                @Param("nextAt") LocalDateTime nextAt);

    @Update("""
            UPDATE sys_knowledge_document
            SET status = 'retired', updated_at = #{now},
                vector_cleanup_status = 'pending',
                vector_cleanup_attempts = 0,
                vector_cleanup_next_at = #{now},
                vector_cleanup_updated_at = #{now}
            WHERE source_key = #{sourceKey} AND status = 'active'
              AND document_id <> #{keepDocumentId}
            """)
    int retireOtherVersions(@Param("sourceKey") String sourceKey,
                            @Param("keepDocumentId") Long keepDocumentId,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_knowledge_document
            SET status = 'retired', updated_at = #{now},
                vector_cleanup_status = 'pending',
                vector_cleanup_attempts = 0,
                vector_cleanup_next_at = #{now},
                vector_cleanup_updated_at = #{now}
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
              AND d.source_type <> 'sdd'
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
              AND d.source_type <> 'sdd'
            ORDER BY d.document_id, c.chunk_index
            LIMIT #{limit}
            """)
    List<KnowledgeSearchRow> findSearchableAll(@Param("limit") int limit);
}
