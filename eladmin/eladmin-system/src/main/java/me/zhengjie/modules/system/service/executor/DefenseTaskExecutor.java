package me.zhengjie.modules.system.service.executor;

import me.zhengjie.modules.system.service.executor.dto.DefenseTaskRequest;

/**
 * Execution boundary for security model training tasks.
 *
 * Real algorithm projects should be integrated by implementing this interface
 * instead of wiring algorithm code directly into controllers or CRUD services.
 *
 * Implementations should persist a structured result JSON with at least:
 * executor, stage, status, message, started_at, finished_at, artifacts.
 * Failed executions should additionally provide error_code and failure_reason.
 */
public interface DefenseTaskExecutor {

    /**
     * Start a pre-training defense task asynchronously.
     *
     * @param request validated pre-defense execution context
     */
    void runPreDefense(DefenseTaskRequest request);

    /**
     * Start an in-training defense task asynchronously.
     *
     * @param request validated during-defense execution context
     */
    void runDuringDefense(DefenseTaskRequest request);
}
