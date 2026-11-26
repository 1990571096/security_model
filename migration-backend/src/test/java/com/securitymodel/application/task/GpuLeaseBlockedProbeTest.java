package com.securitymodel.application.task;

import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GpuLeaseBlockedProbeTest {

    private final GpuLeaseService leaseService = mock(GpuLeaseService.class);
    private final AttemptProcessRegistry registry = mock(AttemptProcessRegistry.class);
    private final GpuLeaseBlockedProbe probe = new GpuLeaseBlockedProbe(leaseService, registry);

    @Test
    void releasesOnlyAfterTheOrphanedTreeIsConfirmedGone() {
        when(leaseService.findBlocked()).thenReturn(List.of(lease()));
        when(registry.reapIfOrphaned(31L, 2)).thenReturn(AttemptProcessRegistry.ReapOutcome.REAPED);

        probe.probe();

        verify(leaseService).releaseByRun(31L, "run-31");
        org.junit.jupiter.api.Assertions.assertNotNull(probe.getLastProbeAt());
    }

    @Test
    void keepsQuarantineWhenIdentityOrKillIsUncertain() {
        when(leaseService.findBlocked()).thenReturn(List.of(lease()));
        when(registry.reapIfOrphaned(31L, 2)).thenReturn(AttemptProcessRegistry.ReapOutcome.UNKNOWN);

        probe.probe();

        verify(leaseService, never()).releaseByRun(31L, "run-31");
        verify(leaseService, never()).markBlocked(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void keepsQuarantineWhenKillFails() {
        when(leaseService.findBlocked()).thenReturn(List.of(lease()));
        when(registry.reapIfOrphaned(31L, 2)).thenReturn(AttemptProcessRegistry.ReapOutcome.KILL_FAILED);

        probe.probe();

        verify(leaseService, never()).releaseByRun(31L, "run-31");
    }

    private GpuLease lease() {
        GpuLease lease = new GpuLease();
        lease.setSlotId("default-gpu-0");
        lease.setTaskId(31L);
        lease.setRunId("run-31");
        lease.setAttempt(2);
        lease.setBlockedAt(LocalDateTime.now().minusMinutes(5));
        lease.setBlockedReason("ORPHANED_PROCESS_UNKNOWN");
        return lease;
    }
}
