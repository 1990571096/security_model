package com.securitymodel.infrastructure.executor;

import com.securitymodel.application.task.DefenseTaskRequest;

public interface DefenseTaskExecutor {

    /**
     * Whether this executor can accept a local attempt. The db-mode API checks this before
     * claiming an attempt or a lease; the Rabbit API leaves the check to the Worker.
     */
    default boolean isConfigured() {
        return true;
    }

    void runPreDefense(DefenseTaskRequest request);

    /**
     * Blocking Worker entry point. It returns only after the Python process has exited and
     * the terminal state has been attempted. A void async return is not a completion signal.
     * Test doubles that only model the legacy async path may keep the default.
     */
    default String executeAndWait(DefenseTaskRequest request) {
        runPreDefense(request);
        return "unknown";
    }

    boolean cancelPreDefense(Long taskId, String runId);
}
