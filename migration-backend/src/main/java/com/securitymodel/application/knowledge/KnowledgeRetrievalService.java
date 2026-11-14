package com.securitymodel.application.knowledge;

import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ACL-first retrieval: only shared documents plus the caller's own private documents
 * enter the candidate set. Ranking never sees rows the caller cannot read.
 */
@Service
public class KnowledgeRetrievalService {

    private final KnowledgeDocumentMapper documentMapper;
    private final CurrentUserService currentUserService;
    private final int candidateLimit;
    private final int topK;
    private final double minCoverage;
    private final double minScore;
    private final boolean adminCrossUserRead;

    public KnowledgeRetrievalService(
            KnowledgeDocumentMapper documentMapper,
            CurrentUserService currentUserService,
            @Value("${security-model.knowledge.candidate-limit:5000}") int candidateLimit,
            @Value("${security-model.knowledge.top-k:5}") int topK,
            @Value("${security-model.knowledge.min-coverage:0.25}") double minCoverage,
            @Value("${security-model.knowledge.min-score:20}") double minScore,
            @Value("${security-model.ownership.admin-cross-user-read:false}") boolean adminCrossUserRead) {
        this.documentMapper = documentMapper;
        this.currentUserService = currentUserService;
        this.candidateLimit = Math.max(1, candidateLimit);
        this.topK = Math.max(1, Math.min(10, topK));
        this.minCoverage = Math.max(0.05, Math.min(1.0, minCoverage));
        this.minScore = Math.max(0, minScore);
        this.adminCrossUserRead = adminCrossUserRead;
    }

    public List<KnowledgeHit> search(String question) {
        AuthenticatedUser identity = currentUserService.requireStableIdentity();
        List<KnowledgeSearchRow> candidates;
        if (identity.admin() && adminCrossUserRead) {
            candidates = documentMapper.findSearchableAll(candidateLimit);
        } else {
            candidates = documentMapper.findSearchableForUser(identity.userId(), candidateLimit);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return KnowledgeRanker.rank(question, candidates, topK, minCoverage, minScore);
    }

    public List<KnowledgeCitation> citations(List<KnowledgeHit> hits) {
        List<KnowledgeCitation> citations = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            KnowledgeSearchRow row = hits.get(index).row();
            citations.add(new KnowledgeCitation(
                    "C" + (index + 1),
                    row.getDocumentId(),
                    row.getSourceType(),
                    row.getSourceKey(),
                    row.getTitle(),
                    row.getVersion(),
                    row.getSectionPath(),
                    row.getChunkIndex(),
                    row.getStartLine(),
                    row.getEndLine(),
                    KnowledgeSanitizer.forModel(row.getContent(), 400),
                    hits.get(index).score(),
                    row.getVisibility(),
                    row.getOwnerUserId()
            ));
        }
        return citations;
    }

    public int topK() {
        return topK;
    }
}
