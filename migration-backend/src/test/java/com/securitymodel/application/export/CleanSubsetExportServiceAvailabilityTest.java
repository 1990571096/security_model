package com.securitymodel.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.task.AfterCommitTaskDispatcher;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CleanSubsetExportServiceAvailabilityTest {

    @TempDir
    Path temp;

    @Test
    void separatesSampleEligibilitySubmitAndDownloadStates() throws Exception {
        Path dataset = Files.createDirectories(temp.resolve("dataset"));
        Path batches = Files.createDirectories(dataset.resolve("cifar-10-batches-py"));
        for (int index = 1; index <= 5; index++) {
            Files.writeString(batches.resolve("data_batch_" + index), "batch");
        }
        Path artifacts = Files.createDirectories(temp.resolve("artifacts"));
        Path selection = Files.writeString(artifacts.resolve("selected_indices_30.npy"), "index");
        Path zip = Files.writeString(artifacts.resolve("clean-subset-30.zip"), "zip");
        ObjectMapper mapper = new ObjectMapper();
        String result = mapper.writeValueAsString(Map.of(
                "algorithm", "vab://pre-defense/ldd",
                "effective_config", Map.of("dataset", "cifar10", "data_path", dataset.toString()),
                "artifacts", Map.of(
                        "path", artifacts.toString(),
                        "selection_kind", "retained_clean_subset",
                        "selection_files", List.of(selection.toString()))));
        PreDefenseTask task = task(result);
        CleanSubsetExportService service = service(mapper);

        Map<String, Object> noExport = service.availability(task, 30, null);
        assertTrue((Boolean) noExport.get("eligible"));
        assertTrue((Boolean) noExport.get("canSubmit"));
        assertFalse((Boolean) noExport.get("downloadReady"));

        CleanSubsetExportTask success = export(30, "success", zip.toString());
        Map<String, Object> readable = service.availability(task, 30, success);
        assertTrue((Boolean) readable.get("downloadReady"));
        assertFalse((Boolean) readable.get("canSubmit"));

        CleanSubsetExportTask missing = export(30, "success",
                artifacts.resolve("missing-30.zip").toString());
        Map<String, Object> invalid = service.availability(task, 30, missing);
        assertFalse((Boolean) invalid.get("downloadReady"));
        assertTrue((Boolean) invalid.get("canSubmit"));
        assertEquals("missing_or_invalid", invalid.get("fileState"));

        PreDefenseTask cifar100 = task(mapper.writeValueAsString(Map.of(
                "algorithm", "vab://pre-defense/ldd",
                "effective_config", Map.of("dataset", "cifar100", "data_path", dataset.toString()),
                "artifacts", Map.of(
                        "path", artifacts.toString(),
                        "selection_kind", "retained_clean_subset",
                        "selection_files", List.of(selection.toString())))));
        Map<String, Object> unsupported = service.availability(cifar100, 30, null);
        assertFalse((Boolean) unsupported.get("eligible"));
        assertFalse((Boolean) unsupported.get("canSubmit"));
        assertTrue(String.valueOf(unsupported.get("reason")).contains("CIFAR-10"));
    }

    private PreDefenseTask task(String cleaningResult) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(9L);
        task.setStatus("success");
        task.setCleaningResult(cleaningResult);
        return task;
    }

    private CleanSubsetExportTask export(int ratio, String status, String filePath) {
        CleanSubsetExportTask export = new CleanSubsetExportTask();
        export.setExportId(3L);
        export.setTaskId(9L);
        export.setIsolationRatio(ratio);
        export.setStatus(status);
        export.setFilePath(filePath);
        return export;
    }

    private CleanSubsetExportService service(ObjectMapper mapper) {
        return new CleanSubsetExportService(
                mock(CleanSubsetExportTaskMapper.class),
                mock(PreDefenseTaskMapper.class),
                mapper,
                mock(AfterCommitTaskDispatcher.class),
                mock(CleanSubsetExportExecutor.class),
                mock(OperationAuditService.class),
                mock(CurrentUserService.class),
                mock(TaskAccessGuard.class));
    }
}
