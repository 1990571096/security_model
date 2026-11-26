package com.securitymodel.interfaces.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record AssistantChatRequest(Long sessionId,
                                   @NotBlank @Size(max = 500) String question,
                                   @Size(max = 64) String scenario,
                                   @Valid FailureContext failureContext,
                                   @Valid ExperimentContext experimentContext) {
    public record FailureContext(@NotNull @Positive Long taskId, @Size(max = 64) String runId,
                                 @Positive Integer attempt) { }

    /** Explicit run binding supplied by the report page or a candidate click. */
    public record ExperimentContext(@Size(max = 3) List<@Valid RunReference> runs,
                                    @DecimalMin("0.01") @DecimalMax("100") BigDecimal ratio) { }

    public record RunReference(@NotNull @Positive Long taskId, @Size(max = 64) String runId,
                               @Positive Integer attempt) { }
}
