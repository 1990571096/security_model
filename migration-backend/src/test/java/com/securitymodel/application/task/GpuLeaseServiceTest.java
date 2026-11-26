package com.securitymodel.application.task;

import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.persistence.mapper.GpuLeaseMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GpuLeaseServiceTest {

    private final GpuLeaseMapper mapper = mock(GpuLeaseMapper.class);
    private final GpuLeaseService service = new GpuLeaseService(mapper, "default-gpu-0", 300);

    @Test
    void refusesToStartWhenTheSlotIsAlreadyLeased() {
        when(mapper.acquire(eq("default-gpu-0"), any(), eq(7L), eq("run-7"), eq(1), any(), any()))
                .thenReturn(0);

        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> service.acquire(7L, "run-7", 1));

        assertTrue(exception.getMessage().contains("GPU_BUSY"));
    }

    @Test
    void failsWhenTheWinningRowCannotBeReadBackWithOurToken() {
        when(mapper.acquire(eq("default-gpu-0"), any(), eq(7L), eq("run-7"), eq(1), any(), any()))
                .thenReturn(1);
        GpuLease other = lease("someone-else");
        when(mapper.findBySlot("default-gpu-0")).thenReturn(other);

        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> service.acquire(7L, "run-7", 1));

        assertTrue(exception.getMessage().contains("GPU_LEASE_UNAVAILABLE"));
    }

    @Test
    void renewAndReleaseAreConditionalOnTheLeaseToken() {
        GpuLease lease = lease("token-a");
        when(mapper.renew(eq("default-gpu-0"), eq("token-a"), any(), any())).thenReturn(1);
        when(mapper.release(eq("default-gpu-0"), eq("token-a"), any())).thenReturn(1);

        assertTrue(service.renew(lease));
        service.release(lease);

        verify(mapper).renew(eq("default-gpu-0"), eq("token-a"), any(), any());
        verify(mapper).release(eq("default-gpu-0"), eq("token-a"), any());
        assertFalse(service.renew(lease("")));
    }

    @Test
    void releaseQuietlySwallowsDatabaseErrors() {
        when(mapper.release(eq("default-gpu-0"), eq("token-a"), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.releaseQuietly("token-a"));
    }

    @Test
    void reportsQuarantinedSlotDistinctlyFromBusy() {
        when(mapper.acquire(eq("default-gpu-0"), any(), eq(7L), eq("run-7"), eq(1), any(), any()))
                .thenReturn(0);
        GpuLease blocked = lease(null);
        blocked.setBlockedAt(LocalDateTime.now());
        blocked.setBlockedReason("ORPHANED_PROCESS_UNKNOWN");
        when(mapper.findBySlot("default-gpu-0")).thenReturn(blocked);

        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> service.acquire(7L, "run-7", 1));

        assertTrue(exception.getMessage().contains("GPU_LEASE_BLOCKED"));
    }

    @Test
    void heartbeatRenewsOnlyLeasesOwnedByThisJvm() {
        String[] acquiredToken = new String[1];
        when(mapper.acquire(eq("default-gpu-0"), any(), eq(7L), eq("run-7"), eq(1), any(), any()))
                .thenAnswer(invocation -> {
                    acquiredToken[0] = invocation.getArgument(1);
                    return 1;
                });
        when(mapper.findBySlot("default-gpu-0"))
                .thenAnswer(invocation -> lease(acquiredToken[0]));
        GpuLease owned = service.acquire(7L, "run-7", 1);
        GpuLease foreign = lease("token-b");
        when(mapper.findActive(any())).thenReturn(java.util.List.of(owned, foreign));

        var active = service.findOwnedActive();

        assertEquals(1, active.size());
        assertEquals(acquiredToken[0], active.get(0).getLeaseToken());
    }

    private GpuLease lease(String token) {
        GpuLease lease = new GpuLease();
        lease.setSlotId("default-gpu-0");
        lease.setLeaseToken(token);
        lease.setTaskId(7L);
        lease.setRunId("run-7");
        lease.setAttempt(1);
        lease.setFencingToken(1L);
        lease.setExpiresAt(LocalDateTime.now().plusSeconds(300));
        return lease;
    }
}
