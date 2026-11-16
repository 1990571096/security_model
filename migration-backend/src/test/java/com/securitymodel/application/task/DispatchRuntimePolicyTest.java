package com.securitymodel.application.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchRuntimePolicyTest {

    @Test
    void dbApiKeepsR3DirectDispatch() {
        DispatchRuntimePolicy policy = new DispatchRuntimePolicy("db", "api");
        assertTrue(policy.directDispatchOwnedByApi());
        assertFalse(policy.writesOutbox());
        assertTrue(policy.runsRecovery());
        assertFalse(policy.runsRelay());
        assertFalse(policy.runsConsumer());
    }

    @Test
    void dbWorkerIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new DispatchRuntimePolicy("db", "worker"));
    }

    @Test
    void rabbitApiWritesOutboxAndNeverExecutesOrRecovers() {
        DispatchRuntimePolicy policy = new DispatchRuntimePolicy("rabbit", "api");
        assertTrue(policy.writesOutbox());
        assertFalse(policy.directDispatchOwnedByApi());
        assertFalse(policy.runsRecovery());
        assertTrue(policy.runsRelay());
        assertFalse(policy.runsConsumer());
    }

    @Test
    void rabbitWorkerConsumesAndRecoversButDoesNotWriteOutbox() {
        DispatchRuntimePolicy policy = new DispatchRuntimePolicy("rabbit", "worker");
        assertFalse(policy.writesOutbox());
        assertTrue(policy.runsConsumer());
        assertTrue(policy.runsRecovery());
        assertFalse(policy.runsRelay());
        assertTrue(policy.workerAcquiresLease());
    }
}
