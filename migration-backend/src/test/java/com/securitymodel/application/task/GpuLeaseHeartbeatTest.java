package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R2-07: losing the lease must cancel the local process rather than let it keep the GPU. */
class GpuLeaseHeartbeatTest {

    private final GpuLeaseService leaseService = mock(GpuLeaseService.class);
    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final com.securitymodel.infrastructure.executor.DefenseTaskExecutor executor =
            mock(com.securitymodel.infrastructure.executor.DefenseTaskExecutor.class);
    private final GpuLeaseHeartbeat heartbeat =
            new GpuLeaseHeartbeat(leaseService, taskMapper, executor, new ObjectMapper());

    @Test
    void failedRenewFailsTheTaskAndCancelsTheLocalProcess() {
        GpuLease lease = new GpuLease();
        lease.setSlotId("default-gpu-0");
        lease.setTaskId(61L);
        lease.setRunId("run-61");
        lease.setAttempt(1);
        lease.setLeaseToken("token-61");
        lease.setExpiresAt(LocalDateTime.now().plusSeconds(300));
        when(leaseService.findOwnedActive()).thenReturn(List.of(lease));
        when(leaseService.renew(lease)).thenReturn(false);
        when(taskMapper.failBeforeExecution(eq(61L), eq("run-61"), eq(1), anyString(), any())).thenReturn(1);

        heartbeat.renewActiveLeases();

        verify(taskMapper).failBeforeExecution(eq(61L), eq("run-61"), eq(1), anyString(), any());
        verify(executor).cancelPreDefense(61L, "run-61");
    }

    @Test
    void healthyLeaseIsRenewedWithoutCancel() {
        GpuLease lease = new GpuLease();
        lease.setTaskId(62L);
        lease.setRunId("run-62");
        lease.setAttempt(1);
        lease.setLeaseToken("token-62");
        when(leaseService.findOwnedActive()).thenReturn(List.of(lease));
        when(leaseService.renew(lease)).thenReturn(true);

        heartbeat.renewActiveLeases();

        verify(taskMapper, never()).failBeforeExecution(anyLong(), anyString(), any(), anyString(), any());
        verify(executor, never()).cancelPreDefense(anyLong(), anyString());
    }
}
