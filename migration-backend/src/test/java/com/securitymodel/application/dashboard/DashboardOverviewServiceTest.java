package com.securitymodel.application.dashboard;

import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.TaskProgressQueryService;
import com.securitymodel.application.task.TaskProgressView;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardOverviewServiceTest {

    private final TaskProgressQueryService tasks = mock(TaskProgressQueryService.class);
    private final ResourceQueryService resources = mock(ResourceQueryService.class);
    private final DashboardOverviewService service = new DashboardOverviewService(tasks, resources);

    @Test
    void overviewUsesRealAggregatesAndDeclaresMetricsUncollected() {
        when(tasks.statistics()).thenReturn(statistics(9L, 2L, 1L, 5L, 1L, 0L, "all-users"));
        when(tasks.trend(7)).thenReturn(List.of(Map.of("date", "2026-11-17", "total", 1L, "success", 0L)));
        when(tasks.successWithResultCount()).thenReturn(4L);
        when(tasks.findPage(isNull(), isNull(), eq(1), eq(5)))
                .thenReturn(new TaskProgressQueryService.TaskPage(List.of(view()), 9L, 1, 5));
        when(resources.findDatasets()).thenReturn(List.of(dataset(true), dataset(false)));
        when(resources.findAlgorithms()).thenReturn(List.of(algorithm("pre", true), algorithm("pre", false), algorithm("post", true)));

        Map<String, Object> overview = service.overview(7);

        assertEquals("all-users", overview.get("scope"));
        assertNotNull(overview.get("sampledAt"));
        Map<?, ?> taskCounts = (Map<?, ?>) overview.get("tasks");
        assertEquals(9L, taskCounts.get("total"));
        assertEquals(2L, taskCounts.get("pending"));
        assertEquals(1L, taskCounts.get("running"));
        assertEquals(5L, taskCounts.get("success"));

        Map<?, ?> resourceCounts = (Map<?, ?>) overview.get("resources");
        assertEquals(2, resourceCounts.get("datasetCount"));
        assertEquals(1L, resourceCounts.get("enabledDatasetCount"));
        assertEquals(3, resourceCounts.get("algorithmCount"));
        assertEquals(2L, resourceCounts.get("enabledAlgorithmCount"));
        Map<?, ?> byStage = (Map<?, ?>) resourceCounts.get("algorithmsByStage");
        assertEquals(2L, byStage.get("pre"));
        assertEquals(0L, byStage.get("during"));
        assertEquals(1L, byStage.get("post"));

        Map<?, ?> readiness = (Map<?, ?>) overview.get("reportReadiness");
        assertEquals(5L, readiness.get("successfulTasks"));
        assertEquals(4L, readiness.get("tasksWithCleaningResult"));
        assertEquals(80.0, readiness.get("ratio"));

        Map<?, ?> metrics = (Map<?, ?>) overview.get("metrics");
        assertEquals(false, metrics.get("available"));
        assertNull(metrics.get("retentionRate"));
        assertNull(metrics.get("attackSuccessRate"));
        assertTrue(metrics.get("note").toString().contains("未采集"));

        List<?> recent = (List<?>) overview.get("recentTasks");
        assertEquals(1, recent.size());
        Map<?, ?> recentRow = (Map<?, ?>) recent.get(0);
        assertEquals(1L, recentRow.get("taskId"));
        assertEquals("真实任务", recentRow.get("taskName"));
        assertFalse(recentRow.containsKey("cleaningResult"));
        assertFalse(recentRow.containsKey("customParams"));
        assertFalse(recentRow.containsKey("createBy"));
        assertFalse(recentRow.containsKey("ownerUserId"));
    }

    @Test
    void emptyWorkspaceReportsNullRatioInsteadOfZeroOrFabricatedNumbers() {
        when(tasks.statistics()).thenReturn(statistics(0L, 0L, 0L, 0L, 0L, 0L, "current-user"));
        when(tasks.trend(7)).thenReturn(List.of());
        when(tasks.successWithResultCount()).thenReturn(0L);
        when(tasks.findPage(isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(new TaskProgressQueryService.TaskPage(List.of(), 0L, 1, 5));
        when(resources.findDatasets()).thenReturn(List.of());
        when(resources.findAlgorithms()).thenReturn(List.of());

        Map<String, Object> overview = service.overview(7);

        assertEquals("current-user", overview.get("scope"));
        assertEquals(0L, ((Map<?, ?>) overview.get("tasks")).get("total"));
        assertNull(((Map<?, ?>) overview.get("reportReadiness")).get("ratio"));
        assertEquals(0, ((Map<?, ?>) overview.get("resources")).get("datasetCount"));
        // sampledAt carries wall-clock milliseconds; scanning the whole response makes this
        // assertion flaky (a millisecond like 24.764 contains the substring "4.7").
        Map<?, ?> metrics = (Map<?, ?>) overview.get("metrics");
        assertFalse(metrics.toString().contains("90.0"));
        assertFalse(metrics.toString().contains("4.7"));
    }

    @Test
    void invalidTrendWindowIsRejectedBeforeQuerying() {
        assertThrows(IllegalArgumentException.class, () -> service.overview(0));
        assertThrows(IllegalArgumentException.class, () -> service.overview(31));
    }

    private Map<String, Object> statistics(long total, long pending, long running, long success,
                                           long failed, long canceled, String scope) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", total);
        stats.put("pendingCount", pending);
        stats.put("runningCount", running);
        stats.put("successCount", success);
        stats.put("failedCount", failed);
        stats.put("canceledCount", canceled);
        stats.put("scope", scope);
        return stats;
    }

    private TaskProgressView view() {
        TaskProgressView view = new TaskProgressView();
        view.setTaskId(1L);
        view.setTaskName("真实任务");
        view.setStatus("success");
        return view;
    }

    private Dataset dataset(boolean enabled) {
        Dataset dataset = new Dataset();
        dataset.setEnabled(enabled);
        return dataset;
    }

    private Algorithm algorithm(String stage, boolean enabled) {
        Algorithm algorithm = new Algorithm();
        algorithm.setDefenseStage(stage);
        algorithm.setEnabled(enabled);
        return algorithm;
    }
}
