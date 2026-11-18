package com.securitymodel.application.task;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness is stricter than process liveness. In db mode the API must have completed
 * startup recovery. In rabbit mode the Worker must also hold the single-machine ownership
 * lock and have started its consumer; otherwise the process is alive but cannot schedule.
 */
@Component("schedulingReadiness")
public class SchedulingReadinessHealthIndicator implements HealthIndicator {

    private final DispatchRuntimePolicy runtimePolicy;
    private final SchedulingReadinessGate readinessGate;
    private final ObjectProvider<WorkerConsumerLifecycle> consumerLifecycleProvider;
    private final ObjectProvider<WorkerOwnershipLease> ownershipLeaseProvider;

    public SchedulingReadinessHealthIndicator(
            DispatchRuntimePolicy runtimePolicy,
            SchedulingReadinessGate readinessGate,
            ObjectProvider<WorkerConsumerLifecycle> consumerLifecycleProvider,
            ObjectProvider<WorkerOwnershipLease> ownershipLeaseProvider) {
        this.runtimePolicy = runtimePolicy;
        this.readinessGate = readinessGate;
        this.consumerLifecycleProvider = consumerLifecycleProvider;
        this.ownershipLeaseProvider = ownershipLeaseProvider;
    }

    @Override
    public Health health() {
        if (!readinessGate.isReady()) {
            return Health.down().withDetail("reason", "RECOVERY_NOT_READY").build();
        }
        if (!runtimePolicy.runsConsumer()) {
            return Health.up()
                    .withDetail("role", runtimePolicy.role().name().toLowerCase())
                    .withDetail("consumerExpected", false)
                    .build();
        }
        WorkerOwnershipLease ownershipLease = ownershipLeaseProvider.getIfAvailable();
        if (ownershipLease == null || !ownershipLease.isHeld()) {
            return Health.down().withDetail("reason", "WORKER_OWNERSHIP_NOT_HELD").build();
        }
        WorkerConsumerLifecycle lifecycle = consumerLifecycleProvider.getIfAvailable();
        if (lifecycle == null) {
            return Health.down().withDetail("reason", "CONSUMER_LIFECYCLE_MISSING").build();
        }
        if (lifecycle.isRecovering()) {
            return Health.down().withDetail("reason", "WORKER_RECOVERING").build();
        }
        if (!lifecycle.isConsumerStarted()) {
            return Health.down().withDetail("reason", "CONSUMER_NOT_STARTED").build();
        }
        return Health.up()
                .withDetail("role", runtimePolicy.role().name().toLowerCase())
                .withDetail("consumerExpected", true)
                .withDetail("consumerStarted", true)
                .build();
    }
}
