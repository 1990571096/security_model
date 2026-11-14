package com.securitymodel.interfaces.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssistantChatRequest(Long sessionId,
                                   @NotBlank @Size(max = 500) String question) {
}
