package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.persistence.mapper.TaskDispatchIntentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskDispatchIntentServiceTest {

    private final TaskDispatchIntentMapper mapper = mock(TaskDispatchIntentMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskDispatchIntentService service =
            new TaskDispatchIntentService(mapper, objectMapper);

    @Test
    void pendingSnapshotExcludesLeaseTokenAndCarriesVersionAndHash() {
        DefenseTaskRequest request = DefenseTaskRequest.builder()
                .taskId(1L).runId("run-1").attempt(1).taskName("LDD")
                .createdBy("tester").gpuLeaseToken("secret-token")
                .gpuSlotId("default-gpu-0").build();

        service.createPending(request, "runtime-hash", LocalDateTime.now());

        ArgumentCaptor<TaskDispatchIntent> captor = ArgumentCaptor.forClass(TaskDispatchIntent.class);
        verify(mapper).insert(captor.capture());
        TaskDispatchIntent stored = captor.getValue();
        assertEquals(TaskDispatchIntent.PENDING, stored.getStatus());
        assertEquals(1, stored.getSchemaVersion());
        assertEquals(1L, stored.getTaskId());
        assertEquals("run-1", stored.getRunId());
        assertEquals("runtime-hash", stored.getRuntimeHash());
        assertFalse(stored.getRequestJson().contains("secret-token"));
        assertFalse(stored.getRequestJson().contains("gpuLeaseToken"));
        assertEquals(64, stored.getRequestHash().length());
    }
}
