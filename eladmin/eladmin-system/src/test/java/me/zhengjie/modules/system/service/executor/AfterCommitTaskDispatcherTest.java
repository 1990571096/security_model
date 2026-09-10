package me.zhengjie.modules.system.service.executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void dispatchesImmediatelyOutsideTransaction() {
        AtomicInteger executions = new AtomicInteger();

        dispatcher.dispatch(executions::incrementAndGet);

        assertEquals(1, executions.get());
    }

    @Test
    void waitsForCommitWhenTransactionSynchronizationIsActive() {
        AtomicInteger executions = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatch(executions::incrementAndGet);

        assertEquals(0, executions.get());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
        assertEquals(1, executions.get());
    }

    @Test
    void rejectsNullTask() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(null));
    }
}
