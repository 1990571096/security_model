package com.securitymodel.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CleanSubsetExportServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void listsOnlyPresentIndexesAndRequiresAllTrainingBatches() throws Exception {
        PreDefenseTask task = exportableTask();
        CleanSubsetExportService service = new CleanSubsetExportService(
                null, null, new ObjectMapper(), null, null, null, null, null);

        assertThat(service.describe(task)).containsEntry("available", true)
                .containsEntry("isolationRatios", List.of(5));

        Files.delete(tempDir.resolve("dataset/cifar-10-batches-py/data_batch_5"));

        assertThat(service.describe(task)).containsEntry("available", false)
                .containsEntry("isolationRatios", List.of());
    }

    @Test
    void reusesAvailableZipAndDispatchesOnlyOneRebuildWhenItDisappears() throws Exception {
        PreDefenseTask task = exportableTask();
        Path zip = tempDir.resolve("artifacts/clean-subset-5.zip");
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new java.util.zip.ZipEntry("metadata.json"));
            output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        CleanSubsetExportTask completed = new CleanSubsetExportTask();
        completed.setExportId(2L);
        completed.setTaskId(1L);
        completed.setStatus("success");
        completed.setFilePath(zip.toString());
        CleanSubsetExportTask pending = new CleanSubsetExportTask();
        pending.setExportId(2L);
        pending.setTaskId(1L);
        pending.setStatus("pending");

        var exportMapper = mock(CleanSubsetExportTaskMapper.class);
        var taskMapper = mock(PreDefenseTaskMapper.class);
        var guard = mock(TaskAccessGuard.class);
        var dispatcher = mock(AfterCommitTaskDispatcher.class);
        var audit = mock(OperationAuditService.class);
        var service = new CleanSubsetExportService(exportMapper, taskMapper, new ObjectMapper(),
                dispatcher, mock(CleanSubsetExportExecutor.class), audit, null, guard);
        when(guard.requireOwnedForUpdate(1L)).thenReturn(task);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(exportMapper.findByTaskAndRatio(1L, 5)).thenReturn(completed, completed, pending);
        when(exportMapper.selectById(2L)).thenReturn(completed, completed, pending);
        when(exportMapper.resetForRetry(2L, "success")).thenReturn(1);

        assertThat(service.resolveDownloadFile(2L)).isEqualTo(zip.toRealPath());
        assertThat(service.submit(1L, 5)).isSameAs(completed);
        verifyNoInteractions(audit, dispatcher);

        Files.delete(zip);
        assertThatThrownBy(() -> service.resolveDownloadFile(2L))
                .isInstanceOf(CleanSubsetExportService.ExportFileUnavailableException.class);
        assertThat(service.submit(1L, 5)).isSameAs(pending);
        assertThat(service.submit(1L, 5)).isSameAs(pending);
        verify(guard, times(2)).requireReadable(1L);
        verify(exportMapper).resetForRetry(2L, "success");
        verify(audit).recordExport(2L, "EXPORT_RETRY", "success", "pending",
                Map.of("taskId", 1L, "isolationRatio", 5), "重试清洗子集导出");
        verify(dispatcher).dispatch(any(), any());
    }

    private PreDefenseTask exportableTask() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path dataset = Files.createDirectory(tempDir.resolve("dataset"));
        Path batches = Files.createDirectory(dataset.resolve("cifar-10-batches-py"));
        for (int number = 1; number <= 5; number++) {
            Files.writeString(batches.resolve("data_batch_" + number), "fixture");
        }
        Path artifacts = Files.createDirectory(tempDir.resolve("artifacts"));
        Path index = Files.writeString(artifacts.resolve("selected_indices_5.npy"), "fixture");
        Path outside = Files.writeString(tempDir.resolve("selected_indices_15.npy"), "fixture");
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(1L);
        task.setStatus("success");
        task.setCleaningResult(mapper.writeValueAsString(Map.of(
                "algorithm", "vab://pre-defense/ldd",
                "effective_config", Map.of("dataset", "cifar10", "data_path", dataset.toString()),
                "artifacts", Map.of("selection_kind", "retained_clean_subset", "path", artifacts.toString(),
                        "selection_files", List.of(index.toString(), outside.toString(),
                                artifacts.resolve("selected_indices_10.npy").toString())))));

        return task;
    }
}
