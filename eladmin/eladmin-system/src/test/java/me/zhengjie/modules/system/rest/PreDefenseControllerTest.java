package me.zhengjie.modules.system.rest;

import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.CleanSubsetExportTask;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.Hyper;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.service.AlgorithmService;
import me.zhengjie.modules.system.service.CleanSubsetExportService;
import me.zhengjie.modules.system.service.CleanSubsetExportTaskService;
import me.zhengjie.modules.system.service.DatasetService;
import me.zhengjie.modules.system.service.HyperService;
import me.zhengjie.modules.system.service.PreDefenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreDefenseControllerTest {

    @Mock
    private PreDefenseService preDefenseService;

    @Mock
    private CleanSubsetExportService cleanSubsetExportService;

    @Mock
    private CleanSubsetExportTaskService cleanSubsetExportTaskService;

    @Mock
    private DatasetService datasetService;

    @Mock
    private AlgorithmService algorithmService;

    @Mock
    private HyperService hyperService;

    private PreDefenseController controller;

    @BeforeEach
    void setUp() {
        controller = new PreDefenseController(
                preDefenseService,
                cleanSubsetExportService,
                cleanSubsetExportTaskService,
                datasetService,
                algorithmService,
                hyperService);
    }

    @Test
    void submitsExportTaskWithAcceptedStatus() {
        PreDefense sourceTask = task();
        CleanSubsetExportTask exportTask = new CleanSubsetExportTask();
        exportTask.setExportId(33L);
        exportTask.setTaskId(12L);
        exportTask.setIsolationRatio(5);
        exportTask.setStatus("pending");

        when(preDefenseService.findById(12L)).thenReturn(sourceTask);
        when(cleanSubsetExportTaskService.submit(sourceTask, 5)).thenReturn(exportTask);

        ResponseEntity<CleanSubsetExportTask> response =
                controller.submitCleanedDatasetExport(12L, 5);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertSame(exportTask, response.getBody());
        verify(cleanSubsetExportTaskService).submit(sourceTask, 5);
    }

    @Test
    void rejectsNegativeLogOffsetBeforeCallingService() {
        assertThrows(
                BadRequestException.class,
                () -> controller.getExecutionLogs(12L, "{\"stdout.log\":-1}"));

        verifyNoInteractions(preDefenseService);
    }

    @Test
    void reportContainsResourcesSnapshotResultAndExportRecords() {
        PreDefense sourceTask = task();
        Dataset dataset = new Dataset();
        Algorithm algorithm = new Algorithm();
        Hyper hyper = new Hyper();
        Map<String, Object> downloadDescription = new LinkedHashMap<>();
        downloadDescription.put("available", true);

        when(preDefenseService.findById(12L)).thenReturn(sourceTask);
        when(datasetService.findById(21L)).thenReturn(dataset);
        when(algorithmService.findById(31L)).thenReturn(algorithm);
        when(hyperService.findById(41L)).thenReturn(hyper);
        when(cleanSubsetExportService.describe(sourceTask)).thenReturn(downloadDescription);
        when(cleanSubsetExportTaskService.findByTaskId(12L))
                .thenReturn(Collections.emptyList());

        ResponseEntity<Object> response = controller.getCleaningReport(12L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> report = (Map<?, ?>) response.getBody();
        assertSame(sourceTask, report.get("taskInfo"));
        assertSame(dataset, report.get("dataset"));
        assertSame(algorithm, report.get("algorithm"));
        assertSame(hyper, report.get("hyperParameter"));
        assertEquals(downloadDescription, report.get("cleanDatasetDownload"));
        assertEquals(Collections.emptyList(), report.get("cleanDatasetExports"));
        Map<?, ?> snapshot = (Map<?, ?>) report.get("executionSnapshot");
        assertEquals("demo-run", snapshot.get("run_id"));
        Map<?, ?> result = (Map<?, ?>) report.get("cleaningResult");
        assertEquals("success", result.get("status"));
    }

    @Test
    void rejectsEmptyBatchStartRequest() {
        assertThrows(
                BadRequestException.class,
                () -> controller.startBatchTasks(Collections.emptySet()));

        verifyNoInteractions(preDefenseService);
    }

    @Test
    void rejectsUnsupportedTaskStatus() {
        assertThrows(
                BadRequestException.class,
                () -> controller.getTasksByStatus("unknown"));

        verifyNoInteractions(preDefenseService);
    }

    @Test
    void rejectsOversizedBatchStartRequest() {
        Set<Long> taskIds = new HashSet<>();
        for (long taskId = 1; taskId <= 101; taskId++) {
            taskIds.add(taskId);
        }

        assertThrows(
                BadRequestException.class,
                () -> controller.startBatchTasks(taskIds));

        verifyNoInteractions(preDefenseService);
    }

    @Test
    void rejectsNullTaskIdInBatchRequest() {
        Set<Long> taskIds = new HashSet<>();
        taskIds.add(null);

        assertThrows(
                BadRequestException.class,
                () -> controller.getBatchTaskProgress(taskIds));

        verifyNoInteractions(preDefenseService);
    }

    private PreDefense task() {
        PreDefense task = new PreDefense();
        task.setTaskId(12L);
        task.setDatasetId(21L);
        task.setAlgorithmId(31L);
        task.setHyperId(41L);
        task.setExecutionSnapshot("{\"run_id\":\"demo-run\"}");
        task.setCleaningResult("{\"status\":\"success\"}");
        return task;
    }
}
