package com.securitymodel.application.task;

import com.securitymodel.domain.task.DispatchConsumption;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchMessageHandlerTest {

    private final DispatchRuntimePolicy policy = new DispatchRuntimePolicy("rabbit", "worker");
    private final TaskOutboxService outboxService = mock(TaskOutboxService.class);
    private final TaskDispatchConsumptionService consumptionService = mock(TaskDispatchConsumptionService.class);
    private final TaskDispatchIntentService intentService = mock(TaskDispatchIntentService.class);
    private final TaskDispatchCoordinator coordinator = mock(TaskDispatchCoordinator.class);
    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final AttemptProcessRegistry processRegistry = mock(AttemptProcessRegistry.class);
    private final DispatchTransport transport = mock(DispatchTransport.class);

    private final DispatchMessageHandler handler = new DispatchMessageHandler(policy, outboxService,
            consumptionService, intentService, coordinator, taskMapper, processRegistry, transport,
            "worker-test", 300, 3);

    @Test
    void terminalTaskAcksOnlyAfterConsumptionRecordIsTerminal() {
        when(outboxService.readEvent(anyString())).thenReturn(event());
        when(consumptionService.claim(any(), anyString(), anyInt(), any()))
                .thenReturn(new TaskDispatchConsumptionService.Claim(
                        TaskDispatchConsumptionService.ClaimState.CLAIMED, "token", null));
        when(taskMapper.selectById(11L)).thenReturn(running(), success());
        when(intentService.findByRun(11L, "run-1", 1)).thenReturn(pendingIntent());
        when(coordinator.consume(any(), any())).thenReturn(TaskDispatchCoordinator.QueueDispatchResult.TERMINAL);
        when(consumptionService.markTerminal(eq("m-1"), eq("token"), eq("success"), anyString(), any()))
                .thenReturn(true);

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle("{}", 0));
        verify(consumptionService).markTerminal(eq("m-1"), eq("token"), eq("success"), anyString(), any());
    }

    @Test
    void gpuBusyReleasesLedgerAndPublishesRetryBeforeAck() {
        when(outboxService.readEvent(anyString())).thenReturn(event());
        when(consumptionService.claim(any(), anyString(), anyInt(), any()))
                .thenReturn(new TaskDispatchConsumptionService.Claim(
                        TaskDispatchConsumptionService.ClaimState.CLAIMED, "token", null));
        when(taskMapper.selectById(11L)).thenReturn(running());
        when(intentService.findByRun(11L, "run-1", 1)).thenReturn(pendingIntent());
        when(coordinator.consume(any(), any())).thenReturn(TaskDispatchCoordinator.QueueDispatchResult.BUSY);
        when(consumptionService.releaseForRetry(eq("m-1"), eq("token"), eq("GPU_BUSY"), any()))
                .thenReturn(true);

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle("{}", 0));
        verify(transport).publishRetry(any(), eq("{}"), eq(1));
    }

    @Test
    void uncertainExecutionIsAckedWithoutRerun() {
        when(outboxService.readEvent(anyString())).thenReturn(event());
        when(consumptionService.claim(any(), anyString(), anyInt(), any()))
                .thenReturn(new TaskDispatchConsumptionService.Claim(
                        TaskDispatchConsumptionService.ClaimState.CLAIMED, "token", null));
        when(taskMapper.selectById(11L)).thenReturn(running());
        when(intentService.findByRun(11L, "run-1", 1)).thenReturn(pendingIntent());
        when(coordinator.consume(any(), any())).thenReturn(TaskDispatchCoordinator.QueueDispatchResult.UNCERTAIN);
        when(consumptionService.markTerminal(eq("m-1"), eq("token"), eq("uncertain"), anyString(), any()))
                .thenReturn(true);

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle("{}", 0));
        verify(consumptionService).markTerminal(eq("m-1"), eq("token"), eq("uncertain"), anyString(), any());
    }

    @Test
    void duplicateDeliveryDoesNotCallCoordinator() {
        when(outboxService.readEvent(anyString())).thenReturn(event());
        when(consumptionService.claim(any(), anyString(), anyInt(), any()))
                .thenReturn(new TaskDispatchConsumptionService.Claim(
                        TaskDispatchConsumptionService.ClaimState.DUPLICATE, null, null));

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle("{}", 0));
        verify(coordinator, org.mockito.Mockito.never()).consume(any(), any());
    }

    @Test
    void poisonPayloadIsRejected() {
        when(outboxService.readEvent(anyString())).thenThrow(new IllegalArgumentException("bad"));
        assertEquals(DispatchMessageHandler.Action.REJECT, handler.handle("{}", 0));
    }

    @Test
    void negativeRetryHeaderIsRejectedBeforeAnyLedgerWrite() {
        assertEquals(DispatchMessageHandler.Action.REJECT, handler.handle("{}", -1));
        verify(outboxService, never()).readEvent(anyString());
    }

    @Test
    void inProgressClaimPublishesDelayedRetryInsteadOfAckingAsCompleted() {
        when(outboxService.readEvent(anyString())).thenReturn(event());
        when(consumptionService.claim(any(), anyString(), anyInt(), any()))
                .thenReturn(new TaskDispatchConsumptionService.Claim(
                        TaskDispatchConsumptionService.ClaimState.IN_PROGRESS, null, null));

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle("{}", 0));
        verify(transport).publishRetryAfter(any(), eq("{}"), eq(0), eq(120));
        verify(coordinator, never()).consume(any(), any());
        verify(consumptionService, never()).markTerminal(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void databaseClaimFailureUsesDelayedRetryAndNotImmediateRequeue() {
        when(outboxService.readEvent(anyString())).thenReturn(event());
        when(consumptionService.claim(any(), anyString(), anyInt(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle("{}", 0));
        verify(transport).publishRetryAfter(any(), eq("{}"), eq(0), eq(120));
        verify(coordinator, never()).consume(any(), any());
    }

    @Test
    void retryExhaustionConvergesTaskAndOutboxBeforeDeadPublish() {
        when(outboxService.readEvent(anyString())).thenReturn(event());
        when(consumptionService.claim(any(), anyString(), anyInt(), any()))
                .thenReturn(new TaskDispatchConsumptionService.Claim(
                        TaskDispatchConsumptionService.ClaimState.CLAIMED, "token", null));
        when(taskMapper.selectById(11L)).thenReturn(running());
        when(intentService.findByRun(11L, "run-1", 1)).thenReturn(pendingIntent());
        when(coordinator.consume(any(), any())).thenReturn(TaskDispatchCoordinator.QueueDispatchResult.BUSY);
        when(consumptionService.markTerminal(eq("m-1"), eq("token"), eq("failed"), anyString(), any()))
                .thenReturn(true);

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle("{}", 3));
        var ordered = inOrder(coordinator, outboxService, consumptionService, transport);
        ordered.verify(coordinator).failAndAbandon(eq(11L), eq("run-1"), eq(1),
                eq("DISPATCH_RETRY_EXHAUSTED_GPU_BUSY"), anyString());
        ordered.verify(outboxService).markDeadByMessageId(eq("m-1"), eq("GPU_BUSY"), any());
        ordered.verify(consumptionService).markTerminal(eq("m-1"), eq("token"), eq("failed"),
                anyString(), any());
        ordered.verify(transport).publishDead(any(), eq("{}"), eq("GPU_BUSY"));
    }

    private DispatchEvent event() {
        return new DispatchEvent(DispatchEvent.SCHEMA_VERSION, "m-1", DispatchEvent.EVENT_TASK_DISPATCH,
                11L, "run-1", 1, null, 7L, "hash", LocalDateTime.now());
    }

    private TaskDispatchIntent pendingIntent() {
        TaskDispatchIntent intent = new TaskDispatchIntent();
        intent.setIntentId(7L);
        intent.setTaskId(11L);
        intent.setRunId("run-1");
        intent.setAttempt(1);
        intent.setStatus(TaskDispatchIntent.PENDING);
        return intent;
    }

    private PreDefenseTask running() {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(11L);
        task.setCurrentRunId("run-1");
        task.setCurrentAttempt(1);
        task.setStatus("running");
        return task;
    }

    private PreDefenseTask success() {
        PreDefenseTask task = running();
        task.setStatus("success");
        return task;
    }
}
