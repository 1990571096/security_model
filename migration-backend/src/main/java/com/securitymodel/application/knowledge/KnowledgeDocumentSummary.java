package com.securitymodel.application.knowledge;

import java.time.LocalDateTime;

public record KnowledgeDocumentSummary(String title, String sourceKey, String sourceType,
                                       String visibility, Integer version, LocalDateTime updatedAt) {
}
