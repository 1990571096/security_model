package com.securitymodel.application.task;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

/**
 * Runs a launch task after the transaction commits. The task itself is expected to be
 * asynchronous at its own boundary (for LDD that is {@code PythonLddTaskExecutor}'s
 * {@code @Async("taskAsync")}); this class deliberately does not submit to the same pool
 * again, otherwise accepting an execution consumes two pool slots and can starve
 * cancellation.
 */
@Component
public class AfterCommitTaskDispatcher {

    public void dispatch(Runnable task, Consumer<RuntimeException> onRejected) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(task, onRejected);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(task, onRejected);
            }
        });
    }

    private void runSafely(Runnable task, Consumer<RuntimeException> onRejected) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            if (onRejected != null) {
                onRejected.accept(exception);
            } else {
                throw exception;
            }
        }
    }
}
