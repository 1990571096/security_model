package com.securitymodel.application.assistant;

import java.util.List;

/**
 * Explicit comparison selection sent from the report page or a candidate click.
 * The first run is the primary; the remaining runs are baselines. A missing runId or
 * attempt means the server may bind the task's current attempt, but once bound it is
 * persisted and reused by follow-up questions.
 */
public record ExperimentSelection(List<RunReference> runs, String ratio) {

    public ExperimentSelection {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    public record RunReference(String taskId, String runId, Integer attempt) {
    }
}
