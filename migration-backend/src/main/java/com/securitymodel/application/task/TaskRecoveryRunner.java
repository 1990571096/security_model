package com.securitymodel.application.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup entry point. In rabbit Worker mode it must acquire the single-machine ownership
 * lock before recovery, and the consumer container is started only after recovery returns.
 */
@Component
public class TaskRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryRunner.class);

    private final DispatchRecoveryCoordinator coordinator;
    private final ObjectProvider<WorkerOwnershipLease> ownershipLease;
    private final ObjectProvider<WorkerConsumerLifecycle> consumerLifecycle;

    public TaskRecoveryRunner(DispatchRecoveryCoordinator coordinator) {
        this(coordinator, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskRecoveryRunner(
            DispatchRecoveryCoordinator coordinator,
            ObjectProvider<WorkerOwnershipLease> ownershipLease,
            ObjectProvider<WorkerConsumerLifecycle> consumerLifecycle) {
        this.coordinator = coordinator;
        this.ownershipLease = ownershipLease;
        this.consumerLifecycle = consumerLifecycle;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedTasks() {
        WorkerOwnershipLease lease = ownershipLease == null ? null : ownershipLease.getIfAvailable();
        if (lease != null && lease.ownershipRequired() && !lease.acquire()) {
            // Do not run recovery or start the consumer without a stable Worker identity.
            log.error("Worker 归属租约未取得，恢复与消费均不启动");
            return;
        }
        coordinator.recoverAtStartup();
        WorkerConsumerLifecycle lifecycle = consumerLifecycle == null
                ? null : consumerLifecycle.getIfAvailable();
        if (lifecycle != null) {
            lifecycle.startAfterRecovery();
        }
    }
}
