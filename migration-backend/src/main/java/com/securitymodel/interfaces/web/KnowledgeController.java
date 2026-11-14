package com.securitymodel.interfaces.web;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.knowledge.KnowledgeIndexService;
import com.securitymodel.application.knowledge.KnowledgeQueryResponse;
import com.securitymodel.application.knowledge.KnowledgeQueryService;
import com.securitymodel.domain.knowledge.KnowledgeDocument;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** R6 knowledge search. Admin-only indexing is enforced in the service, not just the UI. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeQueryService queryService;
    private final KnowledgeIndexService indexService;
    private final KnowledgeDocumentMapper documentMapper;
    private final CurrentUserService currentUserService;

    public KnowledgeController(KnowledgeQueryService queryService,
                               KnowledgeIndexService indexService,
                               KnowledgeDocumentMapper documentMapper,
                               CurrentUserService currentUserService) {
        this.queryService = queryService;
        this.indexService = indexService;
        this.documentMapper = documentMapper;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/query")
    public KnowledgeQueryResponse query(@Valid @RequestBody QueryRequest request) {
        return queryService.query(request.question());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        currentUserService.requireStableIdentity();
        return Map.of("activeDocuments", documentMapper.countActive());
    }

    @PostMapping("/reindex")
    public KnowledgeIndexService.IndexStats reindex() {
        currentUserService.requireAdmin();
        return indexService.reindexSdd();
    }

    @PostMapping("/algorithms/reindex")
    public KnowledgeIndexService.IndexStats reindexAlgorithms() {
        currentUserService.requireAdmin();
        return indexService.reindexAlgorithms();
    }

    /** Owner-only: a private report summary can be made searchable for this user. */
    @PostMapping("/tasks/{taskId}/index")
    public KnowledgeDocument indexTaskReport(@PathVariable Long taskId) {
        currentUserService.requireStableIdentity();
        return indexService.indexTaskReport(taskId);
    }

    @DeleteMapping("/documents/{documentId}")
    public Map<String, Object> retire(@PathVariable Long documentId) {
        currentUserService.requireAdmin();
        return Map.of("retired", indexService.retire(documentId));
    }

    public record QueryRequest(@NotBlank(message = "问题不能为空")
                               @Size(max = 500, message = "问题最多 500 字") String question) {
    }
}
