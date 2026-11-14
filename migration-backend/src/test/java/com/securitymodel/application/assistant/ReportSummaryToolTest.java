package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportSummaryToolTest {

    @Test
    void readsPurityFromMetricsNotFromArtifacts() {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(5L);
        task.setTaskName("report");
        task.setStatus("success");
        task.setCleaningResult("""
                {"error_code":"PYTHON_PROCESS_EXITED",
                 "failure_reason":"Traceback at /workspace/data/secret.log password=abc123 sample=raw-42",
                 "metrics":{"clean_purity_percent_by_isolation_ratio":91.5,
                 "selected_sample_count_by_isolation_ratio":1000},
                 "artifacts":{"selection_kind":"isolation_ratio"},
                 "custom_params":"{\\"api_key\\":\\"sk-short\\",\\"path\\":\\"/data/raw\\"}"}
                """);
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        when(guard.requireOwned(5L)).thenReturn(task);
        CleanSubsetExportService export = mock(CleanSubsetExportService.class);
        when(export.describe(task)).thenReturn(Map.of("available", true, "isolationRatios", List.of(0.1)));

        AssistantToolResult result = new ReportSummaryTool(guard, export, new ObjectMapper())
                .execute(new ObjectMapper().createObjectNode().put("taskId", 5));

        assertEquals(91.5, result.data().path("cleanPurityPercentByIsolationRatio").asDouble());
        assertEquals(1000, result.data().path("selectedSampleCountByIsolationRatio").asInt());
        assertEquals("isolation_ratio", result.data().path("selectionKind").asText());
        assertEquals("执行进程异常退出", result.data().path("failureSummary").asText());
        String payload = result.data().toString();
        assertFalse(payload.contains("/workspace"));
        assertFalse(payload.contains("/data/raw"));
        assertFalse(payload.contains("abc123"));
        assertFalse(payload.contains("raw-42"));
        assertFalse(payload.contains("sk-short"));
    }
}
