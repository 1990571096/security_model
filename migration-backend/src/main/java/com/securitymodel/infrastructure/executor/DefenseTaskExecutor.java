package com.securitymodel.infrastructure.executor;

import com.securitymodel.application.task.DefenseTaskRequest;

public interface DefenseTaskExecutor {
    void runPreDefense(DefenseTaskRequest request);

    boolean cancelPreDefense(Long taskId, String runId);
}
