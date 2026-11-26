package com.securitymodel.application.note;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

/** Request payload for an explicitly saved note. */
public record ExperimentNoteRequest(
        @Size(max = 120) String title,
        @Size(max = 500) String nextStep,
        @Size(max = 64) String requestKey,
        JsonNode runRefs,
        JsonNode snapshot) {
}
