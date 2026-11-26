package com.securitymodel.application.knowledge;

/** Citation returned to the UI; owner visibility is included so the UI can label private sources. */
public record KnowledgeCitation(
        String citationId,
        Long documentId,
        String sourceType,
        String sourceKey,
        String title,
        Integer version,
        String sectionPath,
        Integer chunkIndex,
        Integer startLine,
        Integer endLine,
        String snippet,
        double score,
        String visibility,
        Long ownerUserId
) {
}
