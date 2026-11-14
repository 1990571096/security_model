package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                {"metrics":{"clean_purity_percent_by_isolation_ratio":91.5,
                 "selected_sample_count_by_isolation_ratio":1000},
                 "artifacts":{"selection_kind":"isolation_ratio"}}
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
    }
}
