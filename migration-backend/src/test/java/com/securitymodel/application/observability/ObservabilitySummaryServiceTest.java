package com.securitymodel.application.observability;

import com.securitymodel.application.audit.AdminRequiredException;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.task.GpuLeaseService;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

class ObservabilitySummaryServiceTest {

    private final CurrentUserService currentUserService = org.mockito.Mockito.mock(CurrentUserService.class);
    private final PreDefenseTaskMapper taskMapper = org.mockito.Mockito.mock(PreDefenseTaskMapper.class);
    private final GpuLeaseService gpuLeaseService = org.mockito.Mockito.mock(GpuLeaseService.class);
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void adminSummaryContainsAggregatesButNoIdentityOrTaskIdentifiers() {
        executor.initialize();
        org.mockito.Mockito.when(taskMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(2L);
        org.mockito.Mockito.when(gpuLeaseService.getSlotId()).thenReturn("gpu-0");
        org.mockito.Mockito.when(gpuLeaseService.findBlocked()).thenReturn(List.of());
        org.mockito.Mockito.when(gpuLeaseService.findActive()).thenReturn(List.of(new GpuLease()));
        ObservabilitySummaryService service = new ObservabilitySummaryService(
                currentUserService, taskMapper, executor, gpuLeaseService, registry, 600);

        Map<String, Object> summary = service.summary();

        org.junit.jupiter.api.Assertions.assertEquals("all-users", summary.get("scope"));
        org.junit.jupiter.api.Assertions.assertTrue(summary.containsKey("taskCounts"));
        org.junit.jupiter.api.Assertions.assertTrue(summary.containsKey("gpuSlot"));
        org.junit.jupiter.api.Assertions.assertFalse(summary.toString().contains("taskId"));
        org.junit.jupiter.api.Assertions.assertFalse(summary.toString().contains("userId"));
    }

    @Test
    void nonAdminCannotReadGlobalSummary() {
        org.mockito.Mockito.when(currentUserService.requireAdmin())
                .thenThrow(new AdminRequiredException("admin only"));
        ObservabilitySummaryService service = new ObservabilitySummaryService(
                currentUserService, taskMapper, executor, gpuLeaseService, registry, 600);

        org.junit.jupiter.api.Assertions.assertThrows(AdminRequiredException.class, service::summary);
    }
}
