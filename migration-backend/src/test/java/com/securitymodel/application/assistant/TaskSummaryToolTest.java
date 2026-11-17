package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskSummaryToolTest {

    @Test
    void reportsNonPendingAndMissingExecutorAsBlockers() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        DefenseTaskExecutor executor = mock(DefenseTaskExecutor.class);
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(500L);
        task.setTaskName("cifar10 清洗");
        task.setStatus("failed");
        task.setProgress(40);
        when(guard.requireOwned(500L)).thenReturn(task);
        when(executor.isConfigured()).thenReturn(false);
        TaskSummaryTool tool = new TaskSummaryTool(guard, executor, new ObjectMapper());

        AssistantToolResult result = tool.execute(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("taskId", 500));

        assertEquals(2, result.data().path("blockers").size());
        assertEquals("TASK_NOT_PENDING", result.data().path("blockers").get(0).path("code").asText());
        assertEquals("EXECUTOR_NOT_CONFIGURED", result.data().path("blockers").get(1).path("code").asText());
        assertTrue(result.summary().contains("阻塞项 2 个"));
        assertEquals(KnowledgeSanitizer.forModel("cifar10 清洗", 120),
                result.data().path("taskName").asText());
    }
}
