package com.securitymodel.application.task;

import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cancellation is a control-plane signal and must not share the message consumer thread,
 * which can be blocked by a long Python run. This monitor polls committed canceled rows and
 * asks only the local executor (whose process registry has evidence) to terminate its tree.
 */
@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class WorkerCancellationMonitor {

    private static final Logger log = LoggerFactory.getLogger(WorkerCancellationMonitor.class);

    private final DispatchRuntimePolicy runtimePolicy;
    private final PreDefenseTaskMapper taskMapper;
    private final AttemptProcessRegistry processRegistry;
    private final DefenseTaskExecutor executor;
    private final long lookbackSeconds;

    public WorkerCancellationMonitor(
            DispatchRuntimePolicy runtimePolicy,
            PreDefenseTaskMapper taskMapper,
            AttemptProcessRegistry processRegistry,
            DefenseTaskExecutor executor,
            @Value("${security-model.dispatch.worker.cancel-lookback-seconds:3600}")
            long lookbackSeconds) {
        this.runtimePolicy = runtimePolicy;
        this.taskMapper = taskMapper;
        this.processRegistry = processRegistry;
        this.executor = executor;
        this.lookbackSeconds = lookbackSeconds;
    }

    @Scheduled(fixedDelayString = "${security-model.dispatch.worker.cancel-scan-interval-ms:2000}",
            initialDelayString = "${security-model.dispatch.worker.cancel-scan-interval-ms:2000}")
    public void scan() {
        if (!runtimePolicy.runsConsumer()) {
            return;
        }
        List<PreDefenseTask> canceled = taskMapper.findCanceledSince(
                LocalDateTime.now().minusSeconds(lookbackSeconds), 50);
        for (PreDefenseTask task : canceled) {
            if (task.getCurrentRunId() == null || task.getCurrentAttempt() == null) {
                continue;
            }
            if (processRegistry.isLocallyAccepted(task.getTaskId(), task.getCurrentRunId(),
                    task.getCurrentAttempt())) {
                boolean signaled = executor.cancelPreDefense(task.getTaskId(), task.getCurrentRunId());
                log.info("Worker 取消信号: taskId={}, runId={}, signaled={}",
                        task.getTaskId(), task.getCurrentRunId(), signaled);
            }
        }
    }
}
