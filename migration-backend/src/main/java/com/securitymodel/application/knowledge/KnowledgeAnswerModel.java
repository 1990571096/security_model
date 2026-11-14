package com.securitymodel.application.knowledge;

import java.util.List;

/** External answer generation is optional; retrieval must work with no model configured. */
public interface KnowledgeAnswerModel {

    ModelResult answer(String question, List<KnowledgeCitation> citations);

    record ModelResult(String status, String answer, String model, int attempts) {
        public static ModelResult disabled(String reason) {
            return new ModelResult("model_disabled", reason, null, 0);
        }
    }
}
