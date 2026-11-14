package com.securitymodel.application.knowledge;

/** A ranked chunk plus citation metadata. */
public record KnowledgeHit(KnowledgeSearchRow row, double score, int matchedTerms, double coverage) {
}
