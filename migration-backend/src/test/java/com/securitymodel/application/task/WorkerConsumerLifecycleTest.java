package com.securitymodel.application.task;

import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerConsumerLifecycleTest {

    private final DispatchRuntimePolicy policy = new DispatchRuntimePolicy("rabbit", "worker");
    private final WorkerOwnershipLease lease = mock(WorkerOwnershipLease.class);
    private final DispatchRecoveryCoordinator recovery = mock(DispatchRecoveryCoordinator.class);
    private final AttemptProcessRegistry processRegistry = mock(AttemptProcessRegistry.class);
    private final SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
    private final ObjectProvider<SimpleMessageListenerContainer> containerProvider =
            mock(ObjectProvider.class);
    private final SchedulingReadinessGate gate = new SchedulingReadinessGate();

    private final WorkerConsumerLifecycle lifecycle = new WorkerConsumerLifecycle(
            policy, lease, gate, recovery, processRegistry, containerProvider);

    @Test
    void reacquiredOwnershipRunsRecoveryBeforeStartingConsumer() {
        when(containerProvider.getIfAvailable()).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(lease.isHeld()).thenReturn(false, true);
        when(lease.acquire()).thenReturn(true);
        when(recovery.recoverAtStartup()).thenReturn(safeResult());

        lifecycle.verifyOwnership();

        var ordered = inOrder(recovery, container);
        ordered.verify(recovery).recoverAtStartup();
        ordered.verify(container).start();
    }

    @Test
    void reacquireFailureKeepsConsumerStoppedAndGateClosed() {
        when(containerProvider.getIfAvailable()).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(lease.isHeld()).thenReturn(false);
        when(lease.acquire()).thenReturn(false);

        lifecycle.verifyOwnership();

        verify(container).stop();
        verify(recovery, never()).recoverAtStartup();
        verify(container, never()).start();
        assertFalse(gate.isReady());
    }

    @Test
    void recoveryFailureKeepsConsumerStoppedAndGateClosed() {
        when(containerProvider.getIfAvailable()).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(lease.isHeld()).thenReturn(false);
        when(lease.acquire()).thenReturn(true);
        doThrow(new IllegalStateException("db still unstable"))
                .when(recovery).recoverAtStartup();

        lifecycle.verifyOwnership();

        verify(container, atLeastOnce()).stop();
        verify(container, never()).start();
        assertFalse(gate.isReady());
    }

    @Test
    void stableOwnershipDoesNotRestartAnAlreadyOpenConsumer() {
        when(lease.isHeld()).thenReturn(true);
        when(containerProvider.getIfAvailable()).thenReturn(container);

        lifecycle.startAfterRecovery(safeResult());
        lifecycle.verifyOwnership();

        verify(container).start();
        verify(container, never()).stop();
    }

    @Test
    void locallyAcceptedExecutionDelaysConsumerUntilTheAttemptClears() {
        when(containerProvider.getIfAvailable()).thenReturn(container);
        when(lease.isHeld()).thenReturn(true);
        when(processRegistry.localAcceptedCount()).thenReturn(1, 0);

        assertFalse(lifecycle.startAfterRecovery(safeResult()));
        verify(container, never()).start();

        assertTrue(lifecycle.startAfterRecovery(safeResult()));
        verify(container).start();
    }

    @Test
    void heldOwnershipRerunsRecoveryAfterAcceptedClears() {
        when(containerProvider.getIfAvailable()).thenReturn(container);
        when(lease.isHeld()).thenReturn(true);
        when(processRegistry.localAcceptedCount()).thenReturn(1, 0);
        when(recovery.recoverAtStartup()).thenReturn(safeResult());

        lifecycle.verifyOwnership();
        verify(recovery, never()).recoverAtStartup();
        verify(container, never()).start();

        lifecycle.verifyOwnership();
        verify(recovery).recoverAtStartup();
        verify(container).start();
    }

    @Test
    void startAfterRecoveryOpensConsumerOnlyWhileLeaseIsHeld() {
        when(containerProvider.getIfAvailable()).thenReturn(container);
        when(lease.isHeld()).thenReturn(true);

        lifecycle.startAfterRecovery(safeResult());

        verify(container).start();
    }

    @Test
    void unsafeRecoveryResultKeepsConsumerClosed() {
        when(containerProvider.getIfAvailable()).thenReturn(container);
        when(lease.isHeld()).thenReturn(true);

        assertFalse(lifecycle.startAfterRecovery(new DispatchRecoveryCoordinator.RecoveryResult(
                false, 1, 0, 0, 0, 1, 0, java.util.List.of(), java.util.List.of("11:run-1"))));

        verify(container, never()).start();
        assertFalse(gate.isReady());
    }

    private DispatchRecoveryCoordinator.RecoveryResult safeResult() {
        return new DispatchRecoveryCoordinator.RecoveryResult(
                true, 0, 0, 0, 0, 0, 0, java.util.List.of(), java.util.List.of());
    }
}
