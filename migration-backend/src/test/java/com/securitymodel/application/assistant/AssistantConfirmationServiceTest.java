package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.task.IdempotencyResult;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.assistant.AssistantConfirmation;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.AssistantConfirmationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConfirmationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssistantConfirmationMapper mapper = mock(AssistantConfirmationMapper.class);
    private final AssistantWriteExecutor executor = mock(AssistantWriteExecutor.class);
    private final TaskAccessGuard guard = mock(TaskAccessGuard.class);
    private final AssistantAuditService audit = mock(AssistantAuditService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);

    @Test
    void crossUserConfirmationIsForbidden() {
        AssistantConfirmation confirmation = confirmation(1L, "hash", AssistantConfirmation.PENDING);
        when(mapper.findById(1L)).thenReturn(confirmation);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(2L, "bob", false));

        AssistantException exception = assertThrows(AssistantException.class,
                () -> service().confirm(1L, "token"));

        assertEquals("ASSISTANT_CONFIRMATION_FORBIDDEN", exception.getCode());
    }

    @Test
    void tamperedTokenIsForbidden() {
        AssistantConfirmation confirmation = confirmation(1L,
                AssistantContextHasher.sha256("correct"), AssistantConfirmation.PENDING);
        when(mapper.findById(1L)).thenReturn(confirmation);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "alice", false));

        AssistantException exception = assertThrows(AssistantException.class,
                () -> service().confirm(1L, "tampered"));

        assertEquals("ASSISTANT_CONFIRMATION_INVALID", exception.getCode());
    }

    @Test
    void expiredTokenIsGone() {
        AssistantConfirmation confirmation = confirmation(1L,
                AssistantContextHasher.sha256("token"), AssistantConfirmation.PENDING);
        confirmation.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.findById(1L)).thenReturn(confirmation);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "alice", false));

        AssistantException exception = assertThrows(AssistantException.class,
                () -> service().confirm(1L, "token"));

        assertEquals("ASSISTANT_CONFIRMATION_EXPIRED", exception.getCode());
        assertEquals(HttpStatus.GONE, exception.getStatus());
    }

    @Test
    void consumedTokenReturnsOriginalResultOnce() {
        AssistantConfirmation confirmation = confirmation(1L,
                AssistantContextHasher.sha256("token"), AssistantConfirmation.CONSUMED);
        confirmation.setResultCode(200);
        confirmation.setResultJson("{\"taskId\":1}");
        when(mapper.findById(1L)).thenReturn(confirmation);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "alice", false));

        AssistantConfirmationResult result = service().confirm(1L, "token");

        assertEquals(200, result.status());
        assertTrueReplayed(result);
        assertEquals(1, result.body().path("taskId").asInt());
        verify(executor, org.mockito.Mockito.never()).execute(any(), anyLong(), any());
    }

    @Test
    void changedTaskStateInvalidatesConfirmation() {
        AssistantConfirmation confirmation = confirmation(1L,
                AssistantContextHasher.sha256("token"), AssistantConfirmation.PENDING);
        confirmation.setOperation("start");
        confirmation.setTargetTaskId(5L);
        confirmation.setRequestJson("{\"taskId\":5}");
        confirmation.setContextHash("old");
        when(mapper.findById(1L)).thenReturn(confirmation);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "alice", false));
        when(guard.requireOwnedForUpdate(5L)).thenReturn(task());

        AssistantException exception = assertThrows(AssistantException.class,
                () -> service().confirm(1L, "token"));

        assertEquals("ASSISTANT_CONFIRMATION_STALE", exception.getCode());
        verify(mapper, org.mockito.Mockito.never()).consume(anyLong(), any(), anyLong(), any());
    }

    @Test
    void changedTaskOrRequestStateInvalidatesEveryWriteOperation() {
        for (String operation : java.util.List.of("create", "start", "cancel", "retry", "export", "delete")) {
            AssistantConfirmation confirmation = confirmation(1L,
                    AssistantContextHasher.sha256("token"), AssistantConfirmation.PENDING);
            confirmation.setOperation(operation);
            confirmation.setTargetTaskId("create".equals(operation) ? null : 5L);
            confirmation.setRequestJson("create".equals(operation)
                    ? "{\"taskName\":\"new\"}" : "{\"taskId\":5}");
            confirmation.setContextHash("old");
            when(mapper.findById(1L)).thenReturn(confirmation);
            when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "alice", false));
            when(guard.requireOwnedForUpdate(5L)).thenReturn(task());

            AssistantException exception = assertThrows(AssistantException.class,
                    () -> service().confirm(1L, "token"));

            assertEquals("ASSISTANT_CONFIRMATION_STALE", exception.getCode(), operation);
            verify(executor, org.mockito.Mockito.never()).execute(any(), anyLong(), any());
        }
    }

    @Test
    void confirmedStartExecutesOnceAndStoresResult() throws Exception {
        PreDefenseTask task = task();
        AssistantConfirmation confirmation = confirmation(1L,
                AssistantContextHasher.sha256("token"), AssistantConfirmation.PENDING);
        confirmation.setOperation("start");
        confirmation.setTargetTaskId(5L);
        confirmation.setRequestJson("{\"taskId\":5}");
        confirmation.setContextHash(AssistantContextHasher.taskState("start", task, ""));
        when(mapper.findById(1L)).thenReturn(confirmation);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "alice", false));
        when(guard.requireOwnedForUpdate(5L)).thenReturn(task);
        when(mapper.consume(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(confirmation.getTokenHash()),
                org.mockito.ArgumentMatchers.eq(1L), any(LocalDateTime.class))).thenReturn(1);
        when(executor.execute("start", 1L, objectMapper.readTree("{\"taskId\":5}")))
                .thenReturn(new IdempotencyResult(200, objectMapper.createObjectNode().put("taskId", 5)));

        AssistantConfirmationResult result = service().confirm(1L, "token");

        assertEquals(200, result.status());
        assertFalse(result.replayed());
        verify(mapper).consume(anyLong(), any(), anyLong(), any());
        verify(mapper).markConsumed(anyLong(), any(), any(), any());
    }

    private AssistantConfirmationService service() {
        return new AssistantConfirmationService(mapper, executor, guard, audit,
                currentUser, objectMapper);
    }

    private AssistantConfirmation confirmation(Long userId, String tokenHash, String status) {
        AssistantConfirmation confirmation = new AssistantConfirmation();
        confirmation.setConfirmationId(1L);
        confirmation.setUserId(userId);
        confirmation.setTokenHash(tokenHash);
        confirmation.setStatus(status);
        confirmation.setCreatedAt(LocalDateTime.now());
        confirmation.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        confirmation.setOperation("start");
        return confirmation;
    }

    private PreDefenseTask task() {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(5L);
        task.setStatus("pending");
        task.setCurrentRunId("run-1");
        task.setCurrentAttempt(1);
        task.setCustomParams("{}");
        task.setUpdateTime(LocalDateTime.of(2026, 11, 14, 10, 0));
        return task;
    }

    private void assertTrueReplayed(AssistantConfirmationResult result) {
        org.junit.jupiter.api.Assertions.assertTrue(result.replayed());
    }
}
