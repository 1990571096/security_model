package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.task.RequestIdempotencyRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestIdempotencyServiceTest {

    private final IdempotencyRecordStore store = mock(IdempotencyRecordStore.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequestIdempotencyService service =
            new RequestIdempotencyService(store, currentUserService, objectMapper);

    @Test
    void sameKeySameBodyReplaysStoredResponse() throws Exception {
        Map<String, Object> body = Map.of("taskId", 5L);
        String hash = sha256(objectMapper.writeValueAsString(body));
        RequestIdempotencyRecord record = succeeded(hash, 201, "{\"taskId\":5}");
        authenticate(7L);
        when(store.find(7L, "task.start", "k1")).thenReturn(record);

        IdempotencyResult result = service.execute("task.start", "k1", body, 5L,
                () -> { throw new AssertionError("must not re-run business"); });

        assertEquals(201, result.status());
        assertEquals(5, result.body().path("taskId").asInt());
    }

    @Test
    void interruptedProcessingRecordRequiresManualReconciliation() {
        authenticate(7L);
        RequestIdempotencyRecord failed = new RequestIdempotencyRecord();
        failed.setStatus(RequestIdempotencyRecord.FAILED);
        failed.setRequestHash(sha256("{\"taskId\":5}"));
        when(store.find(7L, "task.start", "k1")).thenReturn(failed);

        IdempotencyConflictException exception = assertThrows(IdempotencyConflictException.class,
                () -> service.execute("task.start", "k1", Map.of("taskId", 5L), 5L,
                        () -> new IdempotencyResult(200, objectMapper.createObjectNode())));

        assertEquals("REQUEST_REQUIRES_RECONCILIATION", exception.getCode());
        verify(store, never()).delete(any());
    }

    @Test
    void sameKeyDifferentBodyIsRejected() {
        authenticate(7L);
        RequestIdempotencyRecord record = succeeded("other-hash", 200, "{}");
        when(store.find(7L, "task.start", "k1")).thenReturn(record);

        IdempotencyConflictException exception = assertThrows(IdempotencyConflictException.class,
                () -> service.execute("task.start", "k1", Map.of("taskId", 5L), 5L,
                        () -> new IdempotencyResult(200, objectMapper.createObjectNode())));

        assertEquals("IDEMPOTENCY_KEY_REUSED", exception.getCode());
    }

    @Test
    void concurrentProcessingRequestIsRejected() throws Exception {
        String hash = sha256(objectMapper.writeValueAsString(Map.of("taskId", 5L)));
        authenticate(7L);
        RequestIdempotencyRecord processing = new RequestIdempotencyRecord();
        processing.setStatus(RequestIdempotencyRecord.PROCESSING);
        processing.setRequestHash(hash);
        when(store.find(7L, "task.start", "k1")).thenReturn(processing);

        IdempotencyConflictException exception = assertThrows(IdempotencyConflictException.class,
                () -> service.execute("task.start", "k1", Map.of("taskId", 5L), 5L,
                        () -> new IdempotencyResult(200, objectMapper.createObjectNode())));

        assertEquals("REQUEST_IN_PROGRESS", exception.getCode());
    }

    @Test
    void firstRequestRecordsSuccessInTheBusinessTransaction() throws Exception {
        authenticate(7L);
        RequestIdempotencyRecord processing = new RequestIdempotencyRecord();
        processing.setRecordId(11L);
        processing.setStatus(RequestIdempotencyRecord.PROCESSING);
        processing.setRequestHash(sha256(objectMapper.writeValueAsString(Map.of("taskId", 5L))));
        when(store.find(7L, "task.start", "k1")).thenReturn(null, processing);
        JsonNode body = objectMapper.createObjectNode().put("accepted", true);

        IdempotencyResult result = service.execute("task.start", "k1", Map.of("taskId", 5L), 5L,
                () -> new IdempotencyResult(200, body));

        assertEquals(200, result.status());
        verify(store).markSucceeded(eq(11L), eq(200), any(), eq(5L), any());
        verify(store, never()).delete(any());
    }

    @Test
    void failedBusinessRequestReleasesTheKey() {
        authenticate(7L);
        RequestIdempotencyRecord processing = new RequestIdempotencyRecord();
        processing.setRecordId(12L);
        processing.setStatus(RequestIdempotencyRecord.PROCESSING);
        processing.setRequestHash(sha256("""
                {"taskId":5}"""));
        when(store.find(7L, "task.start", "k1")).thenReturn(null, processing);
        when(currentUserService.requireStableIdentity())
                .thenReturn(new AuthenticatedUser(7L, "tester", false));

        assertThrows(TaskCommandException.class, () -> service.execute(
                "task.start", "k1", Map.of("taskId", 5L), 5L,
                () -> { throw new TaskCommandException("GPU_BUSY"); }));

        verify(store).delete(12L);
    }

    @Test
    void sameKeyForAnotherUserDoesNotConflict() {
        authenticate(8L);
        when(store.find(8L, "task.start", "k1")).thenReturn(null);
        RequestIdempotencyRecord processing = new RequestIdempotencyRecord();
        processing.setRecordId(13L);
        processing.setStatus(RequestIdempotencyRecord.PROCESSING);
        processing.setRequestHash(sha256("""
                {"taskId":5}"""));
        when(store.find(8L, "task.start", "k1")).thenReturn(null, processing);

        IdempotencyResult result = service.execute("task.start", "k1", Map.of("taskId", 5L), 5L,
                () -> new IdempotencyResult(200, objectMapper.createObjectNode()));

        assertEquals(200, result.status());
        verify(store).insertNew(eq(8L), eq("task.start"), eq("k1"), any(), eq(5L), any());
    }

    private void authenticate(Long userId) {
        when(currentUserService.requireStableIdentity())
                .thenReturn(new AuthenticatedUser(userId, "tester", false));
    }

    private RequestIdempotencyRecord succeeded(String hash, int code, String body) {
        RequestIdempotencyRecord record = new RequestIdempotencyRecord();
        record.setStatus(RequestIdempotencyRecord.SUCCEEDED);
        record.setRequestHash(hash);
        record.setResponseCode(code);
        record.setResponseBody(body);
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
