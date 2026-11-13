package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskExecutionQuotaPolicyTest {

    private final PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    @BeforeEach
    void setUp() {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void perUserQuotaCountsByOwnerIdNotByUsernameSnapshot() {
        TaskExecutionQuotaPolicy policy = policy(4, 2);
        when(mapper.selectList(any())).thenReturn(
                List.of(running(7L, "alice-renamed"), running(7L, "alice")));

        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> policy.validateAndBuildSnapshot(candidate(7L, "alice")));
        assertEquals(true, exception.getMessage().startsWith("TASK_USER_QUOTA_EXCEEDED"));
    }

    @Test
    void anotherOwnerDoesNotConsumeTheQuota() {
        TaskExecutionQuotaPolicy policy = policy(4, 2);
        when(mapper.selectList(any())).thenReturn(List.of(running(8L, "bob")));

        var snapshot = policy.validateAndBuildSnapshot(candidate(7L, "alice"));

        assertEquals(0, snapshot.path("userRunningTasksBeforeClaim").asInt());
        assertEquals(1, snapshot.path("runningTasksBeforeClaim").asInt());
    }

    @Test
    void globalQuotaStillAppliesAcrossOwners() {
        TaskExecutionQuotaPolicy policy = policy(2, 2);
        when(mapper.selectList(any())).thenReturn(List.of(
                running(7L, "alice"), running(8L, "bob")));

        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> policy.validateAndBuildSnapshot(candidate(9L, "carol")));
        assertEquals(true, exception.getMessage().startsWith("TASK_QUOTA_EXCEEDED"));
    }

    @Test
    void shutDownPoolRejectsBeforeQuotaIsEvaluated() {
        TaskExecutionQuotaPolicy policy = policy(4, 2);
        executor.shutdown();
        when(mapper.selectList(any())).thenReturn(List.of());

        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> policy.validateAndBuildSnapshot(candidate(7L, "alice")));
        assertEquals(true, exception.getMessage().startsWith("TASK_QUEUE_UNAVAILABLE"));
    }

    private TaskExecutionQuotaPolicy policy(int maxRunning, int maxPerUser) {
        return new TaskExecutionQuotaPolicy(mapper, executor, objectMapper, maxRunning, maxPerUser, 3600);
    }

    private PreDefenseTask running(Long ownerUserId, String createBy) {
        PreDefenseTask task = new PreDefenseTask();
        task.setStatus("running");
        task.setOwnerUserId(ownerUserId);
        task.setCreateBy(createBy);
        return task;
    }

    private PreDefenseTask candidate(Long ownerUserId, String createBy) {
        PreDefenseTask task = new PreDefenseTask();
        task.setStatus("pending");
        task.setOwnerUserId(ownerUserId);
        task.setCreateBy(createBy);
        return task;
    }
}
