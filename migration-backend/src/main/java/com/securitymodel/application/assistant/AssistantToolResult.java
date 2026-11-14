package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tool output. {@code summary} is the only text sent back to the model; {@code data} is
 * shown to the authenticated user. Write tools return a preview plus a canonical request
 * and context hash, never a business mutation.
 */
public record AssistantToolResult(String summary, JsonNode data, String operation,
                                  JsonNode request, Long targetTaskId, String targetRunId,
                                  String contextHash) {
}
