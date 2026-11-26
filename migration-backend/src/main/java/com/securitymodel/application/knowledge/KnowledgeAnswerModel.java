package com.securitymodel.application.knowledge;

import java.util.List;

/**
 * Provider-neutral answer generation port. The application only knows question + retrieved
 * citations; each adapter owns its own protocol, auth, retry and response parsing. Retrieval
 * must keep working when no adapter can answer.
 *
 * <p>To add a provider, implement this interface and activate it with
 * {@code security-model.knowledge.model.provider}; do not change
 * {@link KnowledgeQueryService} or citation handling.</p>
 */
public interface KnowledgeAnswerModel {

    ModelResult answer(String question, List<KnowledgeCitation> citations);

    record ModelResult(String status, String answer, String model, int attempts) {
        public static ModelResult disabled(String reason) {
            return new ModelResult("model_disabled", reason, null, 0);
        }
    }
}
