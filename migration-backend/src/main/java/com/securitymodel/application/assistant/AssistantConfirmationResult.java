package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;

public record AssistantConfirmationResult(int status, JsonNode body, boolean replayed) {
}
