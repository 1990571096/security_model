package com.securitymodel.application.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AfterCommitTaskDispatcherTest {

    private final AfterCommitTaskDispatcher dispatcher = new AfterCommitTaskDispatcher();

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void dispatchesOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger executions = new AtomicInteger();

        dispatcher.dispatch(executions::incrementAndGet, null);

        assertEquals(0, executions.get());
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        assertEquals(1, executions.get());
    }

    @Test
    void dispatchesImmediatelyWhenNoTransactionSynchronizationIsActive() {
        AtomicInteger executions = new AtomicInteger();

        dispatcher.dispatch(executions::incrementAndGet, null);

        assertEquals(1, executions.get());
    }

    @Test
    void forwardsDispatchFailureToRejectionHandler() {
        RuntimeException failure = new RuntimeException("executor rejected");
        AtomicReference<RuntimeException> received = new AtomicReference<>();

        dispatcher.dispatch(() -> {
            throw failure;
        }, received::set);

        assertSame(failure, received.get());
    }

    @Test
    void propagatesDispatchFailureWhenNoRejectionHandlerIsProvided() {
        RuntimeException failure = new RuntimeException("executor rejected");

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> dispatcher.dispatch(() -> {
                    throw failure;
                }, null));

        assertSame(failure, thrown);
    }
}
