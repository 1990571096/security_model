package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.securitymodel.domain.task.DispatchOutbox;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.persistence.mapper.DispatchOutboxMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOutboxServiceTest {

    private final DispatchOutboxMapper mapper = mock(DispatchOutboxMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TaskOutboxService service = new TaskOutboxService(mapper, objectMapper);

    @Test
    void eventPayloadDoesNotCarryLeaseOrCommandDetails() {
        when(mapper.insert(any(DispatchOutbox.class))).thenReturn(1);
        DispatchOutbox outbox = service.createPending(request(), intent(), LocalDateTime.now());

        assertNotNull(outbox.getMessageId());
        assertEquals(DispatchOutbox.NEW, outbox.getStatus());
        assertEquals(0, outbox.getPublishAttempts());
        assertFalse(outbox.getPayloadJson().contains("gpuLeaseToken"));
        assertFalse(outbox.getPayloadJson().contains("python"));
        assertFalse(outbox.getPayloadJson().contains("request.json"));
        assertTrue(outbox.getPayloadJson().contains(outbox.getMessageId()));
        assertEquals(64, outbox.getPayloadHash().length());
    }

    @Test
    void claimUsesConditionalTokenAndBoundedLease() {
        DispatchOutbox candidate = candidate(1);
        when(mapper.findClaimCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(mapper.markPublishing(any(), anyString(), anyString(), any(), any())).thenReturn(1);

        LocalDateTime now = LocalDateTime.now();
        List<DispatchOutbox> claimed = service.claimBatch(now, "relay-test", 1, 60);

        assertEquals(1, claimed.size());
        assertNotNull(claimed.get(0).getClaimToken());
        assertEquals("relay-test", claimed.get(0).getClaimOwner());
        assertEquals(2, claimed.get(0).getPublishAttempts());
        verify(mapper).markPublishing(eq(1L), eq("relay-test"), anyString(), any(), eq(now));
    }

    @Test
    void staleClaimCannotMarkPublished() {
        DispatchOutbox outbox = candidate(2);
        outbox.setClaimToken("old-token");
        when(mapper.markPublished(eq(1L), eq("old-token"), any())).thenReturn(0);

        assertFalse(service.markPublished(outbox, LocalDateTime.now()));
    }

    @Test
    void expiredPublishingIsReclaimedBeforeClaiming() {
        when(mapper.reclaimExpired(any())).thenReturn(1);
        assertEquals(1, service.reclaimExpired(LocalDateTime.now()));
        verify(mapper).reclaimExpired(any());
    }

    @Test
    void replayRecomputesPayloadHashAndKeepsOldMessageIdForAudit() {
        when(mapper.insert(any(DispatchOutbox.class))).thenReturn(1);
        DispatchOutbox dead = service.createPending(request(), intent(), LocalDateTime.now());
        dead.setStatus(DispatchOutbox.DEAD);
        String oldMessageId = dead.getMessageId();
        when(mapper.findByMessageIdForUpdate(oldMessageId)).thenReturn(dead);
        when(mapper.replayDead(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        DispatchOutbox replayed = service.replayDead(oldMessageId, LocalDateTime.now());

        assertNotNull(replayed);
        assertEquals(64, replayed.getPayloadHash().length());
        assertEquals(replayed.getPayloadHash(),
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "sha256",
                        replayed.getPayloadJson()));
        verify(mapper).replayDead(eq(oldMessageId), anyString(), anyString(),
                eq(replayed.getPayloadHash()), any());
    }

    private DefenseTaskRequest request() {
        DefenseTaskRequest request = new DefenseTaskRequest();
        request.setTaskId(11L);
        request.setRunId("run-1");
        request.setAttempt(1);
        request.setTaskName("test");
        request.setStage("pre_defense");
        request.setCreatedBy("owner");
        return request;
    }

    private TaskDispatchIntent intent() {
        TaskDispatchIntent intent = new TaskDispatchIntent();
        intent.setIntentId(7L);
        intent.setTaskId(11L);
        intent.setRunId("run-1");
        intent.setAttempt(1);
        intent.setRequestHash("a".repeat(64));
        intent.setStatus(TaskDispatchIntent.PENDING);
        return intent;
    }

    private DispatchOutbox candidate(int attempts) {
        DispatchOutbox outbox = new DispatchOutbox();
        outbox.setOutboxId(1L);
        outbox.setMessageId("message-1");
        outbox.setTaskId(11L);
        outbox.setRunId("run-1");
        outbox.setAttempt(1);
        outbox.setStatus(DispatchOutbox.NEW);
        outbox.setPublishAttempts(attempts);
        outbox.setNextAt(LocalDateTime.now().minusSeconds(1));
        outbox.setPayloadJson("{}");
        return outbox;
    }
}
