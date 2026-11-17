package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.task.DispatchRuntimePolicy;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskSummaryToolTest {

    @Test
    void dbModeWithMissingLocalExecutorReportsALaunchBlocker() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        DefenseTaskExecutor executor = mock(DefenseTaskExecutor.class);
        PreDefenseTask task = pendingTask();
        when(guard.requireOwned(500L)).thenReturn(task);
        when(executor.isConfigured()).thenReturn(false);
        TaskSummaryTool tool = new TaskSummaryTool(guard, executor,
                new DispatchRuntimePolicy("db", "api"), new ObjectMapper());

        AssistantToolResult result = tool.execute(arguments());

        assertEquals(1, result.data().path("blockers").size());
        assertEquals("EXECUTOR_NOT_CONFIGURED",
                result.data().path("blockers").get(0).path("code").asText());
        assertEquals("missing", result.data().path("executorConfiguration").asText());
        assertEquals("500", result.data().path("taskIdText").asText());
        assertTrue(result.data().path("blockers").toString().contains("本机执行器未配置"));
        assertTrue(result.summary().contains("阻塞项 1 个"));
    }

    @Test
    void rabbitApiModeDoesNotJudgeWorkerConfigurationAsALocalBlocker() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        DefenseTaskExecutor executor = mock(DefenseTaskExecutor.class);
        PreDefenseTask task = pendingTask();
        when(guard.requireOwned(500L)).thenReturn(task);
        when(executor.isConfigured()).thenReturn(false);
        TaskSummaryTool tool = new TaskSummaryTool(guard, executor,
                new DispatchRuntimePolicy("rabbit", "api"), new ObjectMapper());

        AssistantToolResult result = tool.execute(arguments());

        assertEquals(0, result.data().path("blockers").size());
        assertEquals(1, result.data().path("warnings").size());
        assertEquals("WORKER_CONFIGURATION_UNKNOWN",
                result.data().path("warnings").get(0).path("code").asText());
        assertEquals("worker_unknown", result.data().path("executorConfiguration").asText());
        assertFalse(result.summary().contains("阻塞项 1 个"));
        assertTrue(result.summary().contains("警告 1 条"));
    }

    @Test
    void rabbitWorkerRoleChecksTheExecutorInTheSameProcess() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        DefenseTaskExecutor executor = mock(DefenseTaskExecutor.class);
        PreDefenseTask task = pendingTask();
        when(guard.requireOwned(500L)).thenReturn(task);
        when(executor.isConfigured()).thenReturn(false);
        TaskSummaryTool tool = new TaskSummaryTool(guard, executor,
                new DispatchRuntimePolicy("rabbit", "worker"), new ObjectMapper());

        AssistantToolResult result = tool.execute(arguments());

        assertEquals(1, result.data().path("blockers").size());
        assertEquals("EXECUTOR_NOT_CONFIGURED",
                result.data().path("blockers").get(0).path("code").asText());
    }

    private PreDefenseTask pendingTask() {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(500L);
        task.setTaskName("cifar10 清洗");
        task.setStatus("pending");
        task.setProgress(0);
        return task;
    }

    private JsonNode arguments() {
        return new ObjectMapper().createObjectNode().put("taskId", 500);
    }
}
