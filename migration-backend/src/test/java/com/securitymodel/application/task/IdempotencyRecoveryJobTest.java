package com.securitymodel.application.task;

import com.securitymodel.domain.task.RequestIdempotencyRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyRecoveryJobTest {

    private final IdempotencyRecordStore store = mock(IdempotencyRecordStore.class);
    private final IdempotencyRecoveryJob job = new IdempotencyRecoveryJob(store, 3600, 20);

    @Test
    void staleProcessingRowIsMarkedForReconciliationNotDeleted() {
        RequestIdempotencyRecord stale = new RequestIdempotencyRecord();
        stale.setRecordId(9L);
        stale.setOperation("task.start");
        when(store.findStaleProcessing(any(), anyInt())).thenReturn(List.of(stale));

        job.recoverStaleProcessing();

        verify(store).markStaleProcessingFailed(org.mockito.ArgumentMatchers.eq(9L), any(LocalDateTime.class));
        verify(store, org.mockito.Mockito.never()).delete(any());
    }
}
