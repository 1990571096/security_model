package com.securitymodel.application.knowledge;

import java.util.List;

/** API response: provenance is always present; `answer` is null in retrieval-only mode. */
public record KnowledgeQueryResponse(
        String status,
        String answer,
        List<CitationView> citations,
        String modelStatus,
        String modelMessage
) {
    public record CitationView(
            String citationId,
            String sourceType,
            String sourceKey,
            String title,
            Integer version,
            String sectionPath,
            Integer chunkIndex,
            Integer startLine,
            Integer endLine,
            String snippet,
            String visibility
    ) {
    }
}
