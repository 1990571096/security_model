package com.securitymodel.application.knowledge;

import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACL-first retrieval: only shared documents plus the caller's own private documents
 * enter the candidate set. Ranking never sees rows the caller cannot read.
 */
@Service
public class KnowledgeRetrievalService {

    private final KnowledgeDocumentMapper documentMapper;
    private final CurrentUserService currentUserService;
    private final KnowledgeVectorStore vectorStore;
    private final int candidateLimit;
    private final int topK;
    private final double minCoverage;
    private final double minScore;
    private final boolean adminCrossUserRead;
    private final ThreadLocal<Integer> vectorHitCount = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<String> retrievalMode = ThreadLocal.withInitial(() -> "lexical");

    public KnowledgeRetrievalService(
            KnowledgeDocumentMapper documentMapper,
            CurrentUserService currentUserService,
            @Value("${security-model.knowledge.candidate-limit:5000}") int candidateLimit,
            @Value("${security-model.knowledge.top-k:5}") int topK,
            @Value("${security-model.knowledge.min-coverage:0.25}") double minCoverage,
            @Value("${security-model.knowledge.min-score:20}") double minScore,
            @Value("${security-model.ownership.admin-cross-user-read:false}") boolean adminCrossUserRead) {
        this(documentMapper, currentUserService, null, candidateLimit, topK, minCoverage,
                minScore, adminCrossUserRead);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeRetrievalService(
            KnowledgeDocumentMapper documentMapper,
            CurrentUserService currentUserService,
            KnowledgeVectorStore vectorStore,
            @Value("${security-model.knowledge.candidate-limit:5000}") int candidateLimit,
            @Value("${security-model.knowledge.top-k:5}") int topK,
            @Value("${security-model.knowledge.min-coverage:0.25}") double minCoverage,
            @Value("${security-model.knowledge.min-score:20}") double minScore,
            @Value("${security-model.ownership.admin-cross-user-read:false}") boolean adminCrossUserRead) {
        this.documentMapper = documentMapper;
        this.currentUserService = currentUserService;
        this.vectorStore = vectorStore;
        this.candidateLimit = Math.max(1, candidateLimit);
        this.topK = Math.max(1, Math.min(10, topK));
        this.minCoverage = Math.max(0.05, Math.min(1.0, minCoverage));
        this.minScore = Math.max(0, minScore);
        this.adminCrossUserRead = adminCrossUserRead;
    }

    public List<KnowledgeHit> search(String question) {
        vectorHitCount.set(0);
        retrievalMode.set("lexical");
        AuthenticatedUser identity = currentUserService.requireStableIdentity();
        List<KnowledgeSearchRow> candidates;
        boolean allVisible = identity.admin() && adminCrossUserRead;
        if (allVisible) {
            candidates = documentMapper.findSearchableAll(candidateLimit);
        } else {
            candidates = documentMapper.findSearchableForUser(identity.userId(), candidateLimit);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<KnowledgeHit> lexical = KnowledgeRanker.rank(question, candidates, topK,
                minCoverage, minScore);
        if (lexical.isEmpty()) {
            // The hash-vector channel is a reranker, not a license to answer an unknown
            // question. Keeping the lexical refusal anchor preserves the 20-question set.
            return List.of();
        }
        if (vectorStore == null || !vectorStore.enabled()) {
            return lexical;
        }
        KnowledgeVectorStore.SearchResult vectorResult = vectorStore.search(question,
                Math.max(20, topK * 8), identity.userId(), allVisible);
        if (!vectorResult.available()) {
            return lexical;
        }
        retrievalMode.set("hybrid");
        List<KnowledgeVectorStore.VectorHit> vectorHits = vectorResult.hits();
        vectorHitCount.set(vectorHits.size());
        if (vectorHits.isEmpty()) {
            return lexical;
        }
        Map<Long, KnowledgeSearchRow> allowed = new HashMap<>();
        for (KnowledgeSearchRow row : candidates) {
            allowed.put(row.getChunkId(), row);
        }
        Map<Long, KnowledgeHit> merged = new LinkedHashMap<>();
        for (KnowledgeHit hit : lexical) {
            merged.put(hit.row().getChunkId(), hit);
        }
        for (KnowledgeVectorStore.VectorHit vectorHit : vectorHits) {
            KnowledgeSearchRow row = allowed.get(vectorHit.chunkId());
            double score = vectorHit.score();
            // Redis returns cosine similarity in [0, 1]. Low scores are usually unrelated
            // chunks that share only generic characters.
            if (row == null || score < 0.28) {
                continue;
            }
            KnowledgeHit existing = merged.get(vectorHit.chunkId());
            double fusedScore = Math.max(score * 100.0, existing == null ? 0 : existing.score());
            double fusedCoverage = Math.max(score,
                    existing == null ? 0 : existing.coverage());
            int matchedTerms = existing == null ? 0 : existing.matchedTerms();
            merged.put(vectorHit.chunkId(),
                    new KnowledgeHit(row, fusedScore, matchedTerms, fusedCoverage));
        }
        List<KnowledgeHit> result = new ArrayList<>(merged.values());
        result.sort(java.util.Comparator.comparingDouble(KnowledgeHit::score).reversed()
                .thenComparing(hit -> hit.row().getChunkId()));
        return result.size() <= topK ? result : new ArrayList<>(result.subList(0, topK));
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

    public String retrievalMode() {
        return retrievalMode.get();
    }

    public int vectorHitCount() {
        return vectorHitCount.get();
    }
}
