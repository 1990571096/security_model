package com.securitymodel.application.task;

import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-probes quarantined GPU slots. A slot is released only when the orphaned executor is
 * positively confirmed gone; until then it stays blocked even after the lease TTL expired.
 */
@Component
public class GpuLeaseBlockedProbe {

    private static final Logger log = LoggerFactory.getLogger(GpuLeaseBlockedProbe.class);

    private final GpuLeaseService leaseService;
    private final AttemptProcessRegistry processRegistry;
    private volatile java.time.LocalDateTime lastProbeAt;

    public GpuLeaseBlockedProbe(GpuLeaseService leaseService, AttemptProcessRegistry processRegistry) {
        this.leaseService = leaseService;
        this.processRegistry = processRegistry;
    }

    /** Last time the probe actually ran; surfaced to administrators, never a task detail. */
    public java.time.LocalDateTime getLastProbeAt() {
        return lastProbeAt;
    }

    @Scheduled(fixedDelayString = "${security-model.executor.gpu.blocked-probe-interval-ms:60000}",
            initialDelayString = "${security-model.executor.gpu.blocked-probe-initial-delay-ms:30000}")
    public void probe() {
        lastProbeAt = java.time.LocalDateTime.now();
        java.util.List<GpuLease> blocked;
        try {
            blocked = leaseService.findBlocked();
        } catch (RuntimeException exception) {
            log.warn("GPU 隔离槽位探测跳过：{}", exception.getMessage());
            return;
        }
        for (GpuLease lease : blocked) {
            if (lease.getTaskId() == null || lease.getRunId() == null) {
                continue;
            }
            try {
                var outcome = processRegistry.reapIfOrphaned(lease.getTaskId(), lease.getAttempt());
                if (outcome == AttemptProcessRegistry.ReapOutcome.NOT_STARTED
                        || outcome == AttemptProcessRegistry.ReapOutcome.EXITED
                        || outcome == AttemptProcessRegistry.ReapOutcome.REAPED) {
                    leaseService.releaseByRun(lease.getTaskId(), lease.getRunId());
                    log.info("GPU 槽位隔离解除: taskId={}, runId={}, outcome={}",
                            lease.getTaskId(), lease.getRunId(), outcome);
                } else {
                    log.warn("GPU 槽位仍隔离: taskId={}, runId={}, outcome={}",
                            lease.getTaskId(), lease.getRunId(), outcome);
                }
            } catch (RuntimeException exception) {
                log.warn("GPU 隔离槽位探测失败: taskId={}, reason={}",
                        lease.getTaskId(), exception.getMessage());
            }
        }
    }
}
