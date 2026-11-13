package com.securitymodel.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.ExecutorProcessGuard;
import com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CleanSubsetExportExecutorTest {

    @Test
    void timeoutKillsTheWriteProcessAndNeverMarksThePartialZipSuccessful(@TempDir Path root) throws Exception {
        assumeTrue(pythonAvailable(), "python is not available on this host");
        Path script = root.resolve("stub_export.py");
        Files.writeString(script, String.join("\n",
                "import os, sys, time",
                "out = sys.argv[sys.argv.index('--output') + 1]",
                "open(out, 'wb').write(b'PK\\x03\\x04partial')",
                "open(os.path.join(os.path.dirname(out), 'stub.pid'), 'w').write(str(os.getpid()))",
                "time.sleep(30)"), StandardCharsets.UTF_8);

        Path artifactRoot = Files.createDirectories(root.resolve("artifacts"));
        Path datasetRoot = Files.createDirectories(root.resolve("dataset"));
        Path selection = Files.writeString(root.resolve("selection.json"), "{}");
        CleanSubsetExportTask exportTask = new CleanSubsetExportTask();
        exportTask.setExportId(1L);
        exportTask.setTaskId(11L);
        exportTask.setIsolationRatio(5);
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(11L);
        CleanSubsetExportService.ExportContext context = new CleanSubsetExportService.ExportContext(
                task, exportTask, artifactRoot, datasetRoot, selection);

        CleanSubsetExportTaskMapper mapper = mock(CleanSubsetExportTaskMapper.class);
        CleanSubsetExportService service = mock(CleanSubsetExportService.class);
        when(mapper.claimForExecution(eq(1L), any())).thenReturn(1);
        when(service.resolveContext(1L)).thenReturn(context);

        new CleanSubsetExportExecutor(mapper, service, new ExecutorProcessGuard(new ObjectMapper()),
                "python", script.toString(), 1L).execute(1L);

        verify(mapper).markFailed(eq(1L), eq("EXPORT_FAILED"), anyString(), any());
        Path zip = artifactRoot.resolve("clean-subset-exports").resolve("clean-subset-ratio-5.zip");
        assertFalse(Files.exists(zip), "a half-written ZIP must never become a success download");
        Path pidFile = artifactRoot.resolve("clean-subset-exports").resolve("stub.pid");
        assertTrue(Files.isRegularFile(pidFile), "the short child really ran");
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(java.lang.ProcessHandle.of(pid).map(java.lang.ProcessHandle::isAlive).orElse(false),
                "timeout must terminate the whole child process");
        verify(mapper, org.mockito.Mockito.never()).markSuccess(anyLong(), anyString(), any(), any());
    }

    private boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python", "-c", "print(1)")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }
}
