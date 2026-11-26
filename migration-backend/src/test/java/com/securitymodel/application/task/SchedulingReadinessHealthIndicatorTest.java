package com.securitymodel.application.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulingReadinessHealthIndicatorTest {

    @Test
    void dbApiIsDownUntilRecoveryMarksReady() {
        SchedulingReadinessGate gate = new SchedulingReadinessGate();
        Health health = indicator(new DispatchRuntimePolicy("db", "api"), gate,
                null, null).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "RECOVERY_NOT_READY");
    }

    @Test
    void dbApiIsUpAfterRecovery() {
        SchedulingReadinessGate gate = new SchedulingReadinessGate();
        gate.markReady();

        Health health = indicator(new DispatchRuntimePolicy("db", "api"), gate,
                null, null).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("consumerExpected", false);
    }

    @Test
    void rabbitApiDoesNotRequireAConsumer() {
        SchedulingReadinessGate gate = new SchedulingReadinessGate();
        gate.markReady();

        Health health = indicator(new DispatchRuntimePolicy("rabbit", "api"), gate,
                null, null).health();

        // API owns Outbox writes, not consumption; the Worker readiness is checked on its
        // own process. Requiring a consumer here would deadlock a two-process startup.
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("consumerExpected", false);
    }

    @Test
    void rabbitWorkerIsDownWhenOwnershipIsMissing() {
        SchedulingReadinessGate gate = new SchedulingReadinessGate();
        gate.markReady();
        WorkerConsumerLifecycle lifecycle = mock(WorkerConsumerLifecycle.class);
        when(lifecycle.isConsumerStarted()).thenReturn(true);

        Health health = indicator(new DispatchRuntimePolicy("rabbit", "worker"), gate,
                lifecycle, null).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "WORKER_OWNERSHIP_NOT_HELD");
    }

    @Test
    void rabbitWorkerIsDownWhenConsumerHasNotStarted() {
        SchedulingReadinessGate gate = new SchedulingReadinessGate();
        gate.markReady();
        WorkerConsumerLifecycle lifecycle = mock(WorkerConsumerLifecycle.class);
        when(lifecycle.isConsumerStarted()).thenReturn(false);

        Health health = indicator(new DispatchRuntimePolicy("rabbit", "worker"), gate,
                lifecycle, heldLease()).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "CONSUMER_NOT_STARTED");
    }

    @Test
    void rabbitWorkerIsDownWhileRecovering() {
        SchedulingReadinessGate gate = new SchedulingReadinessGate();
        gate.markReady();
        WorkerConsumerLifecycle lifecycle = mock(WorkerConsumerLifecycle.class);
        when(lifecycle.isRecovering()).thenReturn(true);
        when(lifecycle.isConsumerStarted()).thenReturn(true);

        Health health = indicator(new DispatchRuntimePolicy("rabbit", "worker"), gate,
                lifecycle, heldLease()).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "WORKER_RECOVERING");
    }

    @Test
    void rabbitWorkerIsUpOnlyWhenOwnershipAndConsumerAreReady() {
        SchedulingReadinessGate gate = new SchedulingReadinessGate();
        gate.markReady();
        WorkerConsumerLifecycle lifecycle = mock(WorkerConsumerLifecycle.class);
        when(lifecycle.isConsumerStarted()).thenReturn(true);
        when(lifecycle.isRecovering()).thenReturn(false);

        Health health = indicator(new DispatchRuntimePolicy("rabbit", "worker"), gate,
                lifecycle, heldLease()).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("consumerStarted", true);
    }

    @SuppressWarnings("unchecked")
    private SchedulingReadinessHealthIndicator indicator(
            DispatchRuntimePolicy policy,
            SchedulingReadinessGate gate,
            WorkerConsumerLifecycle lifecycle,
            WorkerOwnershipLease ownershipLease) {
        ObjectProvider<WorkerConsumerLifecycle> lifecycleProvider = mock(ObjectProvider.class);
        ObjectProvider<WorkerOwnershipLease> ownershipProvider = mock(ObjectProvider.class);
        when(lifecycleProvider.getIfAvailable()).thenReturn(lifecycle);
        when(ownershipProvider.getIfAvailable()).thenReturn(ownershipLease);
        return new SchedulingReadinessHealthIndicator(policy, gate, lifecycleProvider, ownershipProvider);
    }

    private WorkerOwnershipLease heldLease() {
        WorkerOwnershipLease lease = mock(WorkerOwnershipLease.class);
        when(lease.isHeld()).thenReturn(true);
        return lease;
    }
}
