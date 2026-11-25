package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.task.PreDefenseTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentComparisonToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void alignsSameRatioAndKeepsUnknownDataVersionAndAttributionBoundary() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        PreDefenseTask first = task(1L, "five epochs", 5);
        PreDefenseTask second = task(2L, "one epoch", 1);
        when(guard.requireReadable(1L)).thenReturn(first);
        when(guard.requireReadable(2L)).thenReturn(second);
        when(reports.getReport(1L)).thenReturn(report(5, Map.of(
                "30", 100.0, "60", 99.99), Map.of("30", 15000, "60", 30000)));
        when(reports.getReport(2L)).thenReturn(report(1, Map.of(
                "30", 100.0, "60", 99.98), Map.of("30", 15000, "60", 30000)));

        ArrayNode ids = mapper.createArrayNode().add("1").add("2");
        AssistantToolResult result = new ExperimentComparisonTool(guard, reports, mapper)
                .execute(mapper.createObjectNode().put("isolationRatio", "30").set("taskIds", ids));

        assertEquals("comparison", result.data().path("kind").asText());
        assertEquals("30", result.data().path("ratio").asText());
        assertEquals(2, result.data().path("runs").size());
        assertEquals("0", result.data().path("deltas").get(0)
                .path("cleanPurityPercentagePoints").asText());
        assertEquals("0", result.data().path("deltas").get(0).path("sampleCountDifference").asText());
        assertTrue(result.data().path("comparability").toString().contains("\"data_version\""));
        assertTrue(result.data().path("gaps").toString().contains("DATA_VERSION_UNKNOWN"));
        assertEquals("GET /api/pre-defense/report/1", result.data().path("evidence").get(0)
                .path("reportEndpoint").asText());
        assertEquals("experiment_report", result.data().path("citations").get(0)
                .path("sourceType").asText());
        assertFalse(result.data().toString().contains("F:\\\\"));
        assertFalse(result.data().toString().contains("data_path"));
    }

    @Test
    void marksMissingRatioInsteadOfInventingAValue() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        when(guard.requireReadable(1L)).thenReturn(task(1L, "a", 5));
        when(guard.requireReadable(2L)).thenReturn(task(2L, "b", 1));
        when(reports.getReport(1L)).thenReturn(report(5, Map.of("30", 100.0), Map.of("30", 15000)));
        when(reports.getReport(2L)).thenReturn(report(1, Map.of(), Map.of()));

        ArrayNode ids = mapper.createArrayNode().add("1").add("2");
        AssistantToolResult result = new ExperimentComparisonTool(guard, reports, mapper)
                .execute(mapper.createObjectNode().put("isolationRatio", "30").set("taskIds", ids));

        assertTrue(result.data().path("deltas").isEmpty());
        assertTrue(result.data().path("gaps").toString().contains("PURITY_NOT_COLLECTED"));
        assertEquals("未采集", result.data().path("runs").get(1)
                .path("cleanPurityPercentByIsolationRatio").path("30").asText("未采集"));
    }

    @Test
    void marksMultipleChangedVariablesWithoutAttributingTheDelta() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        when(guard.requireReadable(1L)).thenReturn(task(1L, "baseline", 5));
        when(guard.requireReadable(2L)).thenReturn(task(2L, "multi-change", 1));
        when(reports.getReport(1L)).thenReturn(report(5, "cifar10", 123,
                Map.of("30", 100.0), Map.of("30", 15000)));
        when(reports.getReport(2L)).thenReturn(report(1, "cifar100", 42,
                Map.of("30", 99.9), Map.of("30", 14000)));

        ArrayNode ids = mapper.createArrayNode().add("1").add("2");
        AssistantToolResult result = new ExperimentComparisonTool(guard, reports, mapper)
                .execute(mapper.createObjectNode().put("isolationRatio", "30").set("taskIds", ids));

        String fields = result.data().path("comparability").toString();
        assertTrue(fields.contains("\"dataset\""));
        assertTrue(fields.contains("\"seed\""));
        assertTrue(fields.contains("\"num_epochs\""));
        assertTrue(fields.contains("\"state\":\"different\""));
        assertEquals("0.1", result.data().path("deltas").get(0)
                .path("cleanPurityPercentagePoints").asText());
        assertTrue(result.data().path("deltas").get(0).path("assertionBoundary").asText()
                .contains("不能归因"));
        assertTrue(result.data().path("gaps").toString().contains("DATA_VERSION_UNKNOWN"));
    }

    @Test
    void stopsDeltasWhenSelectedRunWasReplacedByANewAttempt() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        PreDefenseTask first = task(1L, "baseline", 5);
        PreDefenseTask second = task(2L, "replaced", 1);
        second.setCurrentRunId("run-new");
        second.setCurrentAttempt(2);
        when(guard.requireReadable(1L)).thenReturn(first);
        when(guard.requireReadable(2L)).thenReturn(second);
        when(reports.getReport(1L)).thenReturn(report(5, Map.of("30", 100.0), Map.of("30", 15000)));
        when(reports.getReport(2L)).thenReturn(report(1, Map.of("30", 99.0), Map.of("30", 14000)));

        ArrayNode ids = mapper.createArrayNode().add("1").add("2");
        ArrayNode runs = mapper.createArrayNode();
        runs.addObject().put("taskId", "1").put("runId", "run-1").put("attempt", 1);
        runs.addObject().put("taskId", "2").put("runId", "run-old").put("attempt", 1);
        var arguments = mapper.createObjectNode().put("isolationRatio", "30");
        arguments.set("taskIds", ids);
        arguments.set("runs", runs);
        AssistantToolResult result = new ExperimentComparisonTool(guard, reports, mapper)
                .execute(arguments);

        assertEquals("stale", result.data().path("status").asText());
        assertTrue(result.data().path("deltas").isEmpty());
        assertTrue(result.data().path("gaps").toString().contains("RUN_BINDING_MISMATCH"));
        assertEquals("mismatch", result.data().path("runs").get(1).path("bindingState").asText());
        assertTrue(result.data().path("nextActions").toString().contains("choose_run"));
    }

    @Test
    void stopsDeltasWhenReportIsNotCurrentAttemptEvenWithoutExplicitBinding() {
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        PreDefenseTask first = task(1L, "baseline", 5);
        PreDefenseTask replaced = task(2L, "replaced", 1);
        replaced.setCurrentRunId("run-current");
        replaced.setCurrentAttempt(3);
        when(guard.requireReadable(1L)).thenReturn(first);
        when(guard.requireReadable(2L)).thenReturn(replaced);
        when(reports.getReport(1L)).thenReturn(report(5, Map.of("30", 100.0), Map.of("30", 15000)));
        when(reports.getReport(2L)).thenReturn(report(1, Map.of("30", 99.0), Map.of("30", 14000)));

        ArrayNode ids = mapper.createArrayNode().add("1").add("2");
        AssistantToolResult result = new ExperimentComparisonTool(guard, reports, mapper)
                .execute(mapper.createObjectNode().put("isolationRatio", "30").set("taskIds", ids));

        assertEquals("stale", result.data().path("status").asText());
        assertTrue(result.data().path("deltas").isEmpty());
        assertTrue(result.data().path("gaps").toString().contains("STALE_ATTEMPT"));
    }

    private PreDefenseTask task(Long id, String name, int epochs) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(id);
        task.setTaskName(name);
        task.setStatus("success");
        task.setCurrentRunId("run-" + id);
        task.setCurrentAttempt(1);
        return task;
    }

    private Map<String, Object> report(int epochs, Map<String, Double> purity, Map<String, Integer> counts) {
        return report(epochs, "cifar10", 123, purity, counts);
    }

    private Map<String, Object> report(int epochs, String datasetName, int seed,
                                       Map<String, Double> purity, Map<String, Integer> counts) {
        Map<String, Object> config = Map.of("dataset", datasetName, "seed", seed, "num_epochs", epochs);
        Map<String, Object> metrics = Map.of(
                "clean_purity_percent_by_isolation_ratio", purity,
                "selected_sample_count_by_isolation_ratio", counts);
        Map<String, Object> cleaning = Map.of(
                "run_id", "run-" + (epochs == 5 ? 1 : 2),
                "attempt", 1,
                "status", "success",
                "metrics", metrics,
                "effective_config", config);
        Dataset dataset = new Dataset();
        dataset.setDatasetName("VaB CIFAR-10 Runtime Root");
        return Map.of("cleaningResult", cleaning, "dataset", dataset,
                "executionSnapshot", Map.of("attempts", List.of()));
    }
}
