package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public record AssistantChatResponse(Long sessionId, String status, String answer, String answerSource,
                                    List<AssistantToolTrace> toolResults,
                                    List<AssistantPendingConfirmation> pendingConfirmations,
                                    List<JsonNode> citations, String modelStatus,
                                    String modelMessage, int modelCalls, int toolCalls,
                                    int quotaRemaining, String scenario, JsonNode structured) {

    public record AssistantToolTrace(String toolName, String summary, JsonNode data, Long durationMs, String status) {
        public AssistantToolTrace(String toolName, String summary, JsonNode data, long durationMs) {
            this(toolName, summary, data, durationMs, "ok");
        }
    }

    public record AssistantPendingConfirmation(Long confirmationId, String token,
                                               LocalDateTime expiresAt, String operation,
                                               JsonNode preview) {
    }
}
