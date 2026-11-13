package com.securitymodel.application.task;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup entry point. All classification lives in {@link DispatchRecoveryCoordinator};
 * this class must not fail running tasks before that coordinator has inspected intents.
 */
@Component
public class TaskRecoveryRunner {

    private final DispatchRecoveryCoordinator coordinator;

    public TaskRecoveryRunner(DispatchRecoveryCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedTasks() {
        coordinator.recoverAtStartup();
    }
}
