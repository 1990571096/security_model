package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentSubsetToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void filtersByMinimumAndReflectsRealExportState() {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(7L);
        task.setTaskName("run");
        task.setStatus("success");
        task.setCurrentRunId("run-7");
        task.setCurrentAttempt(1);
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        when(guard.requireReadable(7L)).thenReturn(task);
        CleanSubsetExportTask export = new CleanSubsetExportTask();
        export.setExportId(99L);
        export.setTaskId(7L);
        export.setIsolationRatio(40);
        export.setStatus("running");
        export.setProgress(30);
        Dataset dataset = mock(Dataset.class);
        when(dataset.getDatasetName()).thenReturn("VaB CIFAR-10 Runtime Root");
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        when(reports.getReport(7L)).thenReturn(Map.of(
                "dataset", dataset,
                "cleaningResult", Map.of(
                        "run_id", "run-7", "attempt", 1,
                        "metrics", Map.of(
                                "clean_purity_percent_by_isolation_ratio",
                                Map.of("30", 100.0, "40", 99.99, "50", 99.98),
                                "selected_sample_count_by_isolation_ratio",
                                Map.of("30", 15000, "40", 20000, "50", 25000))),
                "cleanDatasetExports", List.of(export)));
        CleanSubsetExportService exports = mock(CleanSubsetExportService.class);
        when(exports.availability(eq(task), eq(40), eq(export))).thenReturn(Map.of(
                "eligible", true, "canSubmit", false, "downloadReady", false,
                "exportState", "running", "exportLabel", "正在生成", "fileState", "absent",
                "exportId", "99", "exportProgress", 30));
        when(exports.availability(eq(task), eq(50), any())).thenReturn(Map.of(
                "eligible", false, "canSubmit", false, "downloadReady", false,
                "exportState", "not_requested", "exportLabel", "不可导出", "fileState", "absent",
                "reason", "当前子集导出仅支持 CIFAR-10 的 LDD 结果。"));

        AssistantToolResult result = new ExperimentSubsetTool(guard, reports, exports, mapper)
                .execute(mapper.createObjectNode().put("taskId", "7").put("minimumSamples", 20000));

        assertEquals("subset_selection", result.data().path("kind").asText());
        assertEquals("40", result.data().path("recommendedRatio").asText());
        assertEquals(2, result.data().path("available").size());
        assertEquals(1, result.data().path("excluded").size());
        assertEquals("30", result.data().path("excluded").get(0).path("isolationRatio").asText());
        assertEquals("running", result.data().path("available").get(0).path("exportState").asText());
        assertFalse(result.data().path("available").get(0).path("downloadReady").asBoolean());
        assertFalse(result.data().path("available").get(1).path("eligibleForExport").asBoolean());
        assertEquals(99L, result.data().path("available").get(0).path("exportId").asLong());
        assertEquals(1, result.data().path("eligibleExportCount").asInt());
        assertTrue(result.data().path("evidence").path("reportEndpoint").asText()
                .startsWith("GET /api/pre-defense/report/7"));
    }
}
