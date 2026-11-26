package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public record AssistantPreviewRequest(@NotBlank String toolName, JsonNode arguments) {
}
