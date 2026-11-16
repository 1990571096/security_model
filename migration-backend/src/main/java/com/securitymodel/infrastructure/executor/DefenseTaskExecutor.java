package com.securitymodel.infrastructure.executor;

import com.securitymodel.application.task.DefenseTaskRequest;

public interface DefenseTaskExecutor {
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
