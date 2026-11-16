package com.securitymodel.application.task;

import com.securitymodel.domain.task.DispatchOutbox;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOutboxRelayTest {

    private final DispatchRuntimePolicy policy = new DispatchRuntimePolicy("rabbit", "api");
    private final TaskOutboxService outboxService = mock(TaskOutboxService.class);
    private final DispatchTransport transport = mock(DispatchTransport.class);
    private final TaskDispatchCoordinator dispatchCoordinator = mock(TaskDispatchCoordinator.class);
    private final TaskOutboxRelay relay = new TaskOutboxRelay(policy, outboxService, transport,
            dispatchCoordinator, 20, 60, 3, "5,30,120");

    @Test
    void confirmedPublishMarksPublished() {
        DispatchOutbox outbox = outbox(1);
        when(outboxService.claimBatch(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(outbox));
        when(outboxService.readEvent("{}")).thenReturn(event());
        when(outboxService.markPublished(eq(outbox), any())).thenReturn(true);

        assertEquals(1, relay.relayOnce());
        verify(transport).publish(any(), eq("{}"));
        verify(outboxService).markPublished(eq(outbox), any());
    }

    @Test
    void failedPublishUsesBackoffWhileAttemptsRemain() {
        DispatchOutbox outbox = outbox(1);
        when(outboxService.claimBatch(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(outbox));
        when(outboxService.readEvent("{}")).thenReturn(event());
        doThrow(new IllegalStateException("broker down")).when(transport).publish(any(), eq("{}"));
        when(outboxService.markRetry(eq(outbox), anyString(), any(), any())).thenReturn(true);

        assertEquals(0, relay.relayOnce());
        verify(outboxService).markRetry(eq(outbox), anyString(), any(), any());
    }

    @Test
    void exhaustedPublishMarksDeadButKeepsTaskReplayable() {
        DispatchOutbox outbox = outbox(3);
        when(outboxService.claimBatch(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(outbox));
        when(outboxService.readEvent("{}")).thenReturn(event());
        doThrow(new IllegalStateException("broker down")).when(transport).publish(any(), eq("{}"));
        when(outboxService.markDead(eq(outbox), anyString(), any())).thenReturn(true);

        assertEquals(0, relay.relayOnce());
        verify(outboxService).markDead(eq(outbox), anyString(), any());
        verify(dispatchCoordinator, org.mockito.Mockito.never()).failAndAbandon(any(Long.class),
                anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void unroutablePublishIsDeadImmediatelyAndConvergesTask() {
        DispatchOutbox outbox = outbox(1);
        when(outboxService.claimBatch(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(outbox));
        when(outboxService.readEvent("{}")).thenReturn(event());
        doThrow(new IllegalStateException("RABBIT_UNROUTABLE: 消息不可路由"))
                .when(transport).publish(any(), eq("{}"));
        when(outboxService.markDead(eq(outbox), anyString(), any())).thenReturn(true);

        assertEquals(0, relay.relayOnce());
        verify(outboxService).markDead(eq(outbox), anyString(), any());
        verify(dispatchCoordinator).failAndAbandon(eq(11L), eq("run-1"), eq(1),
                eq("DISPATCH_PUBLISH_FAILED"), anyString());
    }

    private DispatchOutbox outbox(int attempts) {
        DispatchOutbox outbox = new DispatchOutbox();
        outbox.setOutboxId(1L);
        outbox.setMessageId("m-1");
        outbox.setPayloadJson("{}");
        outbox.setStatus(DispatchOutbox.PUBLISHING);
        outbox.setClaimToken("token");
        outbox.setPublishAttempts(attempts);
        return outbox;
    }

    private DispatchEvent event() {
        return new DispatchEvent(DispatchEvent.SCHEMA_VERSION, "m-1", DispatchEvent.EVENT_TASK_DISPATCH,
                11L, "run-1", 1, null, 7L, "hash", LocalDateTime.now());
    }
}
