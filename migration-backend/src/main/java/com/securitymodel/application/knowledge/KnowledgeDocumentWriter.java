package com.securitymodel.application.knowledge;

import com.securitymodel.domain.knowledge.KnowledgeChunk;
import com.securitymodel.domain.knowledge.KnowledgeDocument;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One short transaction per document version: insert the new version and all chunks,
 * then retire every other active version of the same source. Readers only ever see
 * rows with {@code status='active'}, so there is no half-built version visible.
 */
@Component
public class KnowledgeDocumentWriter {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;

    public KnowledgeDocumentWriter(KnowledgeDocumentMapper documentMapper,
                                   KnowledgeChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public KnowledgeDocument saveVersion(String sourceType, String sourceKey, String title,
                                         String visibility, Long ownerUserId, String contentHash,
                                         List<MarkdownKnowledgeChunker.Draft> drafts,
                                         LocalDateTime now) {
        KnowledgeDocument active = documentMapper.findActiveBySourceKey(sourceKey);
        int version = active == null ? 1 : active.getVersion() + 1;
        KnowledgeDocument document = new KnowledgeDocument();
        document.setSourceType(sourceType);
        document.setSourceKey(sourceKey);
        document.setVersion(version);
        document.setTitle(title);
        document.setVisibility(visibility);
        document.setOwnerUserId(ownerUserId);
        document.setContentHash(contentHash);
        document.setStatus(KnowledgeDocument.STATUS_ACTIVE);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);

        int index = 0;
        for (MarkdownKnowledgeChunker.Draft draft : drafts) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getDocumentId());
            chunk.setChunkIndex(index++);
            chunk.setSectionPath(draft.sectionPath());
            chunk.setContent(draft.content());
            chunk.setStartLine(draft.startLine());
            chunk.setEndLine(draft.endLine());
            chunk.setCreatedAt(now);
            chunkMapper.insert(chunk);
        }
        documentMapper.retireOtherVersions(sourceKey, document.getDocumentId(), now);
        return document;
    }

    @Transactional
    public boolean retire(Long documentId, LocalDateTime now) {
        return documentMapper.retireById(documentId, now) == 1;
    }
}
