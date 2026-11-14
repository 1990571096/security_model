package com.securitymodel.application.knowledge;

import org.springframework.stereotype.Service;

import java.util.List;

/** Orchestrates retrieval and optional generation; generation never replaces provenance. */
@Service
public class KnowledgeQueryService {

    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeAnswerModel answerModel;

    public KnowledgeQueryService(KnowledgeRetrievalService retrievalService,
                                 KnowledgeAnswerModel answerModel) {
        this.retrievalService = retrievalService;
        this.answerModel = answerModel;
    }

    public KnowledgeQueryResponse query(String rawQuestion) {
        String question = normalizeQuestion(rawQuestion);
        List<KnowledgeHit> hits = retrievalService.search(question);
        if (hits.isEmpty()) {
            return new KnowledgeQueryResponse("unknown",
                    "知识库中没有足够依据回答该问题。",
                    List.of(), "not_called", "未检索到可引用片段");
        }
        List<KnowledgeCitation> citations = retrievalService.citations(hits);
        KnowledgeAnswerModel.ModelResult modelResult;
        try {
            modelResult = answerModel.answer(question, citations);
        } catch (RuntimeException exception) {
            modelResult = new KnowledgeAnswerModel.ModelResult(
                    "model_unavailable", "外部模型调用失败，已降级为检索片段", null, 0);
        }
        if ("answered".equals(modelResult.status())) {
            String answer = KnowledgePromptBuilder.sanitizeAnswer(modelResult.answer(), citations);
            if (answer != null) {
                return new KnowledgeQueryResponse("answered", answer, views(citations),
                        modelResult.status(), "答案由外部模型基于检索片段生成");
            }
            return new KnowledgeQueryResponse("retrieval_only", null, views(citations),
                    "model_invalid_citation", "模型引用校验失败，已降级为检索片段");
        }
        return new KnowledgeQueryResponse("retrieval_only", null, views(citations),
                modelResult.status(), modelResult.answer());
    }

    private String normalizeQuestion(String raw) {
        String question = KnowledgeSanitizer.sanitize(raw);
        if (question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (question.length() > 500) {
            throw new IllegalArgumentException("问题最多 500 字");
        }
        return question;
    }

    private List<KnowledgeQueryResponse.CitationView> views(List<KnowledgeCitation> citations) {
        return citations.stream().map(citation -> new KnowledgeQueryResponse.CitationView(
                citation.citationId(),
                citation.sourceType(),
                citation.sourceKey(),
                citation.title(),
                citation.version(),
                citation.sectionPath(),
                citation.chunkIndex(),
                citation.startLine(),
                citation.endLine(),
                citation.snippet(),
                citation.visibility()
        )).toList();
    }
}
