package com.securitymodel.application.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeQueryServiceTest {

    private KnowledgeRetrievalService retrievalService;
    private KnowledgeCitation citation;
    private KnowledgeHit hit;

    @BeforeEach
    void setUp() {
        retrievalService = mock(KnowledgeRetrievalService.class);
        KnowledgeSearchRow row = new KnowledgeSearchRow();
        row.setChunkId(1L);
        row.setDocumentId(1L);
        row.setChunkIndex(0);
        row.setSourceKey("sdd:05-decisions.md");
        row.setSourceType("sdd");
        row.setTitle("决策");
        row.setVersion(1);
        row.setVisibility("shared");
        row.setSectionPath("R2");
        row.setContent("租约过期不得直接接管。");
        row.setStartLine(1);
        row.setEndLine(2);
        hit = new KnowledgeHit(row, 30.0, 2, 0.5);
        citation = new KnowledgeCitation("C1", 1L, "sdd", "sdd:05-decisions.md",
                "决策", 1, "R2", 0, 1, 2, "租约过期不得直接接管。", 30.0, "shared", null);
        when(retrievalService.citations(any())).thenReturn(List.of(citation));
    }

    @Test
    void unknownQuestionNeverCallsModel() {
        AtomicInteger calls = new AtomicInteger();
        when(retrievalService.search(anyString())).thenReturn(List.of());
        KnowledgeQueryService service = new KnowledgeQueryService(retrievalService, (question, citations) -> {
            calls.incrementAndGet();
            return new KnowledgeAnswerModel.ModelResult("answered", "should not run", "m", 1);
        });

        KnowledgeQueryResponse response = service.query("火星基地氧气参数");

        assertEquals("unknown", response.status());
        assertEquals(0, calls.get());
        assertTrue(response.citations().isEmpty());
    }

    @Test
    void disabledModelReturnsRetrievalOnlyWithCitations() {
        when(retrievalService.search(anyString())).thenReturn(List.of(hit));
        KnowledgeQueryService service = new KnowledgeQueryService(retrievalService,
                (question, citations) -> KnowledgeAnswerModel.ModelResult.disabled("外部模型未启用"));

        KnowledgeQueryResponse response = service.query("租约过期");

        assertEquals("retrieval_only", response.status());
        assertNull(response.answer());
        assertEquals(1, response.citations().size());
        assertEquals("C1", response.citations().get(0).citationId());
    }

    @Test
    void validModelAnswerIsReturnedWithProvenance() {
        when(retrievalService.search(anyString())).thenReturn(List.of(hit));
        KnowledgeQueryService service = new KnowledgeQueryService(retrievalService,
                (question, citations) -> new KnowledgeAnswerModel.ModelResult(
                        "answered", "依据 [C1] 不得直接接管。", "m", 1));

        KnowledgeQueryResponse response = service.query("租约过期");

        assertEquals("answered", response.status());
        assertTrue(response.answer().contains("[C1]"));
        assertEquals(1, response.citations().size());
    }

    @Test
    void fabricatedCitationFallsBackToRetrievalOnly() {
        when(retrievalService.search(anyString())).thenReturn(List.of(hit));
        KnowledgeQueryService service = new KnowledgeQueryService(retrievalService,
                (question, citations) -> new KnowledgeAnswerModel.ModelResult(
                        "answered", "编造 [C9] 的结果", "m", 1));

        KnowledgeQueryResponse response = service.query("租约过期");

        assertEquals("retrieval_only", response.status());
        assertEquals("model_invalid_citation", response.modelStatus());
        assertNull(response.answer());
    }

    @Test
    void modelExceptionFallsBackInsteadOfFailingTheRequest() {
        when(retrievalService.search(anyString())).thenReturn(List.of(hit));
        KnowledgeQueryService service = new KnowledgeQueryService(retrievalService,
                (question, citations) -> {
                    throw new IllegalStateException("boom");
                });

        KnowledgeQueryResponse response = service.query("租约过期");

        assertEquals("retrieval_only", response.status());
        assertEquals("model_unavailable", response.modelStatus());
    }
}
