package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftNextRunToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesEffectiveConfigAndProvesOnlyEpochsChanged() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        ValidateLddParametersTool validator = mock(ValidateLddParametersTool.class);
        PreDefenseTask task = task();
        when(guard.requireReadable(7L)).thenReturn(task);
        Map<String, Object> config = Map.of(
                "dataset", "cifar10", "seed", 123, "num_epochs", 5,
                "batch_size", 128, "r", 0.75, "data_path", "F:\\secret\\dataset");
        when(reports.getReport(7L)).thenReturn(Map.of("cleaningResult", Map.of(
                "run_id", "run-7", "attempt", 1, "effective_config", config)));
        when(validator.execute(any())).thenAnswer(invocation -> {
            ObjectNode args = invocation.getArgument(0);
            ObjectNode params = args.path("customParams").deepCopy();
            ObjectNode data = mapper.createObjectNode().put("valid", true);
            data.set("normalizedParameters", params);
            return new AssistantToolResult("valid", data, null, null, 7L, "run-7", null);
        });

        AssistantToolResult result = new DraftNextRunTool(guard, reports, validator, mapper)
                .execute(mapper.createObjectNode().put("taskId", "7")
                        .put("runId", "run-7").put("attempt", 1));

        assertEquals("cleaningResult.effective_config", result.data().path("baselineSource").asText());
        assertEquals(5, result.data().path("before").asInt());
        assertEquals(6, result.data().path("after").asInt());
        assertEquals("run-7", result.data().path("baselineRunId").asText());
        assertTrue(result.data().path("reproducible").asBoolean());
        assertEquals("num_epochs", result.data().path("deltaCheck").path("changedFields").get(0).asText());
        assertFalse(result.data().path("proposedParams").has("data_path"));
        assertTrue(result.data().path("formPath").asText().contains("prefillRunId=run-7"));
    }

    @Test
    void rejectsMissingEffectiveConfigInsteadOfDefaultingEpochs() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        ValidateLddParametersTool validator = mock(ValidateLddParametersTool.class);
        when(guard.requireReadable(7L)).thenReturn(task());
        when(reports.getReport(7L)).thenReturn(Map.of("cleaningResult", Map.of(
                "run_id", "run-7", "attempt", 1)));

        AssistantException exception = assertThrows(AssistantException.class, () ->
                new DraftNextRunTool(guard, reports, validator, mapper)
                        .execute(mapper.createObjectNode().put("taskId", "7")));
        assertEquals("BASELINE_CONFIG_UNAVAILABLE", exception.getCode());
    }

    @Test
    void rejectsWhenSelectedRunNoLongerMatchesReport() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        ValidateLddParametersTool validator = mock(ValidateLddParametersTool.class);
        when(guard.requireReadable(7L)).thenReturn(task());
        when(reports.getReport(7L)).thenReturn(Map.of("cleaningResult", Map.of(
                "run_id", "run-new", "attempt", 2,
                "effective_config", Map.of("num_epochs", 5))));

        AssistantException exception = assertThrows(AssistantException.class, () ->
                new DraftNextRunTool(guard, reports, validator, mapper)
                        .execute(mapper.createObjectNode().put("taskId", "7")
                                .put("runId", "run-old").put("attempt", 1)));
        assertEquals("BASELINE_RUN_STALE", exception.getCode());
    }

    private PreDefenseTask task() {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(7L);
        task.setTaskName("baseline");
        task.setStatus("success");
        task.setCurrentRunId("run-7");
        task.setCurrentAttempt(1);
        task.setDatasetId(1L);
        task.setAlgorithmId(2L);
        task.setHyperId(3L);
        return task;
    }
}
