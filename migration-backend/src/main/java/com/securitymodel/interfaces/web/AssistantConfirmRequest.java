package com.securitymodel.interfaces.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AssistantConfirmRequest(@NotNull Long confirmationId,
                                       @NotBlank @Size(max = 128) String token) {
}
