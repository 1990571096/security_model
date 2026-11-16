package com.securitymodel.application.task;

import java.time.LocalDateTime;

/**
 * Event v1. It deliberately carries no command line, absolute path, credential or
 * full parameter snapshot; the Worker reloads the authoritative request from the intent.
 */
public record DispatchEvent(
        Integer schemaVersion,
        String messageId,
        String eventType,
        Long taskId,
        String runId,
        Integer attempt,
        Long ownerUserId,
        Long intentId,
        String requestHash,
        LocalDateTime occurredAt) {

    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TASK_DISPATCH = "task.dispatch";
}
