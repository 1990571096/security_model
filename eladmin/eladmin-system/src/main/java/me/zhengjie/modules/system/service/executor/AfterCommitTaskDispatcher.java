package me.zhengjie.modules.system.service.executor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

/**
 * Dispatches an asynchronous task only after the surrounding transaction commits.
 *
 * <p>When the caller is outside a transaction, the task is dispatched immediately.
 * This keeps recovery and unit-test usage simple while preventing an executor from
 * observing a task row that is still uncommitted.</p>
 */
@Component
public class AfterCommitTaskDispatcher {

    public void dispatch(Runnable task) {
        dispatch(task, error -> {
            throw error;
        });
    }

    /**
     * Dispatches a task after commit and reports executor rejection to the
     * caller so the business task can be converged to a failure state.
     */
    public void dispatch(Runnable task, Consumer<RuntimeException> onRejected) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        Consumer<RuntimeException> rejectionHandler = onRejected == null
                ? error -> { throw error; }
                : onRejected;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(task, rejectionHandler);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(task, rejectionHandler);
            }
        });
    }

    private void runSafely(Runnable task, Consumer<RuntimeException> onRejected) {
        try {
            task.run();
        } catch (RuntimeException e) {
            onRejected.accept(e);
        }
    }
}
