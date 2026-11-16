package com.securitymodel.application.task;

import com.securitymodel.domain.task.DispatchConsumption;
import com.securitymodel.infrastructure.persistence.mapper.DispatchConsumptionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDispatchConsumptionServiceTest {

    private final DispatchConsumptionMapper mapper = mock(DispatchConsumptionMapper.class);
    private final TaskDispatchConsumptionService service = new TaskDispatchConsumptionService(mapper);

    @Test
    void terminalMessageIsDuplicateAndNeverRunsAgain() {
        when(mapper.insertProcessing(anyString(), any(), anyString(), any(), anyString(),
                anyString(), any(), anyString(), any())).thenThrow(new DuplicateKeyException("dup"));
        DispatchConsumption existing = existing(DispatchConsumption.TERMINAL);
        when(mapper.findByMessageId("m-1")).thenReturn(existing);

        TaskDispatchConsumptionService.Claim claim = service.claim(event(), "worker-1", 300,
                LocalDateTime.now());

        assertEquals(TaskDispatchConsumptionService.ClaimState.DUPLICATE, claim.state());
    }

    @Test
    void processingClaimIsHeldUntilExpiry() {
        when(mapper.insertProcessing(anyString(), any(), anyString(), any(), anyString(),
                anyString(), any(), anyString(), any())).thenThrow(new DuplicateKeyException("dup"));
        DispatchConsumption existing = existing(DispatchConsumption.PROCESSING);
        existing.setClaimUntil(LocalDateTime.now().plusMinutes(5));
        when(mapper.findByMessageId("m-1")).thenReturn(existing);

        TaskDispatchConsumptionService.Claim claim = service.claim(event(), "worker-1", 300,
                LocalDateTime.now());

        assertEquals(TaskDispatchConsumptionService.ClaimState.IN_PROGRESS, claim.state());
    }

    @Test
    void retryRowCanBeClaimedByNextDelivery() {
        when(mapper.insertProcessing(anyString(), any(), anyString(), any(), anyString(),
                anyString(), any(), anyString(), any())).thenThrow(new DuplicateKeyException("dup"));
        DispatchConsumption existing = existing(DispatchConsumption.RETRY);
        when(mapper.findByMessageId("m-1")).thenReturn(existing);
        when(mapper.reclaimExpired(eq("m-1"), eq("worker-1"), eq("worker-1"), any(), anyString(), any()))
                .thenReturn(1);

        TaskDispatchConsumptionService.Claim claim = service.claim(event(), "worker-1", 300,
                LocalDateTime.now());

        assertEquals(TaskDispatchConsumptionService.ClaimState.CLAIMED, claim.state());
        assertNotNull(claim.claimToken());
    }

    @Test
    void terminalWriteRequiresClaimToken() {
        when(mapper.markTerminal(eq("m-1"), eq("token"), eq("success"), eq("TASK_SUCCESS"), any()))
                .thenReturn(1);
        assertTrue(service.markTerminal("m-1", "token", "success", "TASK_SUCCESS",
                LocalDateTime.now()));
        assertFalse(service.markTerminal("m-1", "other", "success", "TASK_SUCCESS",
                LocalDateTime.now()));
        verify(mapper).markTerminal(eq("m-1"), eq("token"), eq("success"), eq("TASK_SUCCESS"), any());
    }

    private DispatchEvent event() {
        return new DispatchEvent(DispatchEvent.SCHEMA_VERSION, "m-1", DispatchEvent.EVENT_TASK_DISPATCH,
                11L, "run-1", 1, null, 7L, "hash", LocalDateTime.now());
    }

    private DispatchConsumption existing(String status) {
        DispatchConsumption consumption = new DispatchConsumption();
        consumption.setMessageId("m-1");
        consumption.setTaskId(11L);
        consumption.setRunId("run-1");
        consumption.setAttempt(1);
        consumption.setStatus(status);
        return consumption;
    }
}
