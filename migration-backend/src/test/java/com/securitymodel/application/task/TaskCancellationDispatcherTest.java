package com.securitymodel.application.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCancellationDispatcherTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void saturatedDedicatedQueueFallsBackToCallerInsteadOfDroppingCancellation()
            throws Exception {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("cancel-test-");
        executor.setRejectedExecutionHandler((task, pool) -> task.run());
        executor.initialize();

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
        executor.execute(() -> { }); // occupies the single queue slot

        TaskCancellationDispatcher dispatcher = new TaskCancellationDispatcher(executor);
        CountDownLatch signal = new CountDownLatch(1);
        dispatcher.dispatch(signal::countDown);

        assertTrue(signal.await(1, TimeUnit.SECONDS),
                "a full cancellation queue must run the signal on the caller, not drop it");
        releaseWorker.countDown();
    }

    @Test
    void signalIsSubmittedOnlyAfterCommit() {
        TaskCancellationDispatcher dispatcher = new TaskCancellationDispatcher();
        java.util.concurrent.atomic.AtomicInteger signals = new java.util.concurrent.atomic.AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            dispatcher.dispatch(signals::incrementAndGet);
            assertEquals(0, signals.get(), "cancellation must not fire while the transaction is open");
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertEquals(1, signals.get());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
