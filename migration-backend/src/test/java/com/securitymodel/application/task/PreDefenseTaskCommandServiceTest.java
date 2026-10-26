package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class PreDefenseTaskCommandServiceTest {

    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final ResourceQueryService resourceQueryService = mock(ResourceQueryService.class);
    private final DefenseTaskExecutor defenseTaskExecutor = mock(DefenseTaskExecutor.class);
    private final OperationAuditService operationAuditService = mock(OperationAuditService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
    private PreDefenseTaskCommandService commandService;

    @BeforeEach
    void setUp() {
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setQueueCapacity(1);
        taskExecutor.initialize();

        TaskExecutionQuotaPolicy quotaPolicy = new TaskExecutionQuotaPolicy(
                taskMapper, taskExecutor, objectMapper, 4, 2, 3600);
        ExecutionFingerprintService fingerprintService = new ExecutionFingerprintService(
                objectMapper, "test-workspace", "python", "algorithm_entry.py", false, 1024);
        commandService = new PreDefenseTaskCommandService(
                taskMapper,
                resourceQueryService,
                defenseTaskExecutor,
                new LddConfigurationPolicy(objectMapper),
                quotaPolicy,
                fingerprintService,
                new AfterCommitTaskDispatcher(),
                objectMapper,
                operationAuditService,
                currentUserService);

        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(resourceQueryService.findDataset(1L)).thenReturn(dataset());
        when(resourceQueryService.findAlgorithm(2L)).thenReturn(algorithm());
        when(currentUserService.username()).thenReturn("tester");
    }

    @AfterEach
    void shutDownExecutor() {
        taskExecutor.shutdown();
    }

    @Test
    void retryAppendsANewAttemptWithoutChangingPreviousSnapshot() throws Exception {
        String priorAttempt = """
                {"attempt":1,"runId":"previous-run","request":{"customParams":"{\\"seed\\":11}"},
                 "snapshotHash":"previous-hash"}
                """;
        String existingSnapshot = objectMapper.createObjectNode()
                .put("version", 2)
                .set("attempts", objectMapper.createArrayNode()
                        .add(objectMapper.readTree(priorAttempt)))
                .toString();
        PreDefenseTask failed = task(31L, "failed", existingSnapshot);
        PreDefenseTask pending = task(31L, "pending", existingSnapshot);

        when(taskMapper.selectById(31L)).thenReturn(failed, pending);
        when(taskMapper.resetForRetry(31L)).thenReturn(1);
        when(taskMapper.claimForExecution(eq(31L), any(), eq(2), any(), any())).thenReturn(1);

        commandService.retry(31L);

        ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).claimForExecution(
                eq(31L), any(), eq(2), any(), snapshotCaptor.capture());
        JsonNode attempts = objectMapper.readTree(snapshotCaptor.getValue()).path("attempts");
        assertEquals(2, attempts.size());
        assertEquals("previous-run", attempts.get(0).path("runId").asText());
        assertEquals("previous-hash", attempts.get(0).path("snapshotHash").asText());
        assertEquals(11, attempts.get(0).path("request").path("customParams").isTextual()
                ? objectMapper.readTree(attempts.get(0).path("request").path("customParams").asText())
                        .path("seed").asInt()
                : -1);
        assertEquals(2, attempts.get(1).path("attempt").asInt());
        assertNotEquals("previous-run", attempts.get(1).path("runId").asText());
        verify(defenseTaskExecutor).runPreDefense(any(DefenseTaskRequest.class));
    }

    @Test
    void rejectedExecutorDispatchMarksOnlyTheCurrentAttemptFailed() throws Exception {
        PreDefenseTask pending = task(32L, "pending", null);
        when(taskMapper.selectById(32L)).thenReturn(pending);
        when(taskMapper.claimForExecution(eq(32L), any(), eq(1), any(), any())).thenReturn(1);
        doThrow(new RejectedExecutionException("queue full"))
                .when(defenseTaskExecutor).runPreDefense(any(DefenseTaskRequest.class));
        when(taskMapper.failBeforeExecution(eq(32L), any(), eq(1), any(), any())).thenReturn(1);

        commandService.start(32L);

        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> failureCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).failBeforeExecution(
                eq(32L), runIdCaptor.capture(), eq(1), failureCaptor.capture(), any());
        JsonNode failure = objectMapper.readTree(failureCaptor.getValue());
        assertEquals("failed", failure.path("status").asText());
        assertEquals("TASK_QUEUE_FULL", failure.path("error_code").asText());
        assertEquals(runIdCaptor.getValue(), failure.path("run_id").asText());
        assertEquals(1, failure.path("attempt").asInt());
    }

    private PreDefenseTask task(Long taskId, String status, String executionSnapshot) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(taskId);
        task.setTaskName("LDD reliability test");
        task.setStatus(status);
        task.setDatasetId(1L);
        task.setAlgorithmId(2L);
        task.setCustomParams("{\"seed\":11}");
        task.setCreateBy("tester");
        task.setCreateTime(LocalDateTime.now());
        task.setExecutionSnapshot(executionSnapshot);
        return task;
    }

    private Dataset dataset() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(1L);
        dataset.setDatasetName("CIFAR-10 test");
        dataset.setDatasetType("image");
        dataset.setFilePath("dataset://cifar10");
        dataset.setEnabled(true);
        dataset.setParseStatus("success");
        return dataset;
    }

    private Algorithm algorithm() {
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmId(2L);
        algorithm.setAlgorithmName("LDD");
        algorithm.setAlgorithmType("pre_defense");
        algorithm.setAlgorithmCategory("data_cleaning");
        algorithm.setDefenseStage("pre");
        algorithm.setImplementationPath("vab://pre-defense/ldd");
        algorithm.setEnabled(true);
        algorithm.setDefaultConfig("""
                {"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,
                 "poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,
                 "num_epochs":5,"lr_poison":0.01,"r":0.1,"alpha1":1.0,"alpha2":1.0,
                 "seed":11}
                """);
        return algorithm;
    }
}
