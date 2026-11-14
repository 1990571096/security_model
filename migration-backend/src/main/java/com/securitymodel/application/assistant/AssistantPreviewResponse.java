package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record AssistantPreviewResponse(Long confirmationId, String token, LocalDateTime expiresAt,
                                      String operation, JsonNode preview) {
}
