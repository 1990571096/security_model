package com.securitymodel.application.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

/**
 * Runs post-commit work outside the transaction. The task executor is deliberately
 * asynchronous: a long-running LDD process must not keep the HTTP start request (or the
 * transaction connection) occupied until the process finishes. Tests and non-Spring
 * callers may use the no-arg constructor, which keeps the original synchronous behavior.
 */
@Component
public class AfterCommitTaskDispatcher {

    private final ThreadPoolTaskExecutor taskExecutor;

    public AfterCommitTaskDispatcher() {
        this.taskExecutor = null;
    }

    @Autowired
    public AfterCommitTaskDispatcher(@Qualifier("taskAsync") ThreadPoolTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public void dispatch(Runnable task, Consumer<RuntimeException> onRejected) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(task, onRejected);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(task, onRejected);
            }
        });
    }

    private void submit(Runnable task, Consumer<RuntimeException> onRejected) {
        if (taskExecutor == null) {
            runSafely(task, onRejected);
            return;
        }
        try {
            taskExecutor.execute(() -> runSafely(task, onRejected));
        } catch (RuntimeException rejection) {
            if (onRejected == null) {
                throw rejection;
            }
            onRejected.accept(rejection);
        }
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
