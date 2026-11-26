package com.securitymodel.application.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cancellation is a control-plane signal, not training work. It must never queue behind
 * long-running LDD/export/dispatch jobs, therefore it has its own bounded executor.
 * A full queue falls back to the committing thread, so the signal is delayed but never
 * silently dropped. Tests and non-Spring callers use the no-arg constructor and run inline.
 */
@Component
public class TaskCancellationDispatcher {

    private final ThreadPoolTaskExecutor cancellationExecutor;

    public TaskCancellationDispatcher() {
        this.cancellationExecutor = null;
    }

    @Autowired
    public TaskCancellationDispatcher(
            @Qualifier("cancelAsync") ThreadPoolTaskExecutor cancellationExecutor) {
        this.cancellationExecutor = cancellationExecutor;
    }

    public void dispatch(Runnable signal) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(signal);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(signal);
            }
        });
    }

    private void submit(Runnable signal) {
        if (cancellationExecutor == null) {
            signal.run();
            return;
        }
        try {
            cancellationExecutor.execute(signal);
        } catch (RuntimeException rejected) {
            // Executor shutdown or a custom rejection handler that did not run inline:
            // the committing thread is the final fallback so cancellation cannot be lost.
            signal.run();
        }
    }
}
