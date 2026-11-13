package com.securitymodel.application.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CleanSubsetExportExecutor {

    private final CleanSubsetExportTaskMapper mapper;
    private final CleanSubsetExportService service;
    private final String pythonExecutable;
    private final String pythonScript;
    private final long timeoutSeconds;

    public CleanSubsetExportExecutor(
            CleanSubsetExportTaskMapper mapper,
            @Lazy CleanSubsetExportService service,
            @Value("${security-model.executor.python.executable:python}") String pythonExecutable,
            @Value("${security-model.executor.python.script:}") String pythonScript,
            @Value("${security-model.executor.python.timeout-seconds:3600}") long timeoutSeconds) {
        this.mapper = mapper;
        this.service = service;
        this.pythonExecutable = pythonExecutable;
        this.pythonScript = pythonScript;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Async("taskAsync")
    public void execute(Long exportId) {
        if (mapper.claimForExecution(exportId, LocalDateTime.now()) != 1) {
            return;
        }
        CleanSubsetExportService.ExportContext context = null;
        try {
            context = service.resolveContext(exportId);
            if (pythonScript == null || pythonScript.isBlank()) {
                throw new IllegalStateException("未配置 Python 算法适配器");
            }
            Path outputDir = context.artifactRoot().resolve("clean-subset-exports").normalize();
            Files.createDirectories(outputDir);
            String prefix = "clean-subset-ratio-" + context.exportTask().getIsolationRatio();
            Path zip = outputDir.resolve(prefix + ".zip");
            Path metadata = outputDir.resolve(prefix + ".metadata.json");
            Path zipPart = outputDir.resolve(prefix + ".zip.part");
            Path metadataPart = outputDir.resolve(prefix + ".metadata.json.part");
            Files.deleteIfExists(zipPart);
            Files.deleteIfExists(metadataPart);
            Path log = outputDir.resolve(prefix + ".export.log");
            mapper.updateProgress(exportId, 20);
            Process process = new ProcessBuilder(List.of(
                    pythonExecutable, pythonScript, "--export-clean-subset",
                    "--selection-file", context.selectionFile().toString(),
                    "--dataset-root", context.datasetRoot().toString(),
                    "--dataset", "cifar10",
                    "--output", zipPart.toString(),
                    "--metadata", metadataPart.toString()))
                    .directory(new File(context.artifactRoot().toString()))
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                // Kill the whole tree: a Python child writing the ZIP must not survive and
                // keep appending to a file the task has already reported as failed.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("导出清洗数据集超时");
            }
            mapper.updateProgress(exportId, 85);
            if (process.exitValue() != 0 || !Files.isRegularFile(zipPart) || Files.size(zipPart) == 0) {
                throw new IllegalStateException("Python 导出失败，请查看导出日志");
            }
            Files.move(zipPart, zip, StandardCopyOption.REPLACE_EXISTING);
            if (Files.isRegularFile(metadataPart)) {
                Files.move(metadataPart, metadata, StandardCopyOption.REPLACE_EXISTING);
            }
            mapper.markSuccess(exportId, zip.toAbsolutePath().toString(),
                    Files.exists(metadata) ? metadata.toAbsolutePath().toString() : null, LocalDateTime.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            mapper.markFailed(exportId, "EXPORT_INTERRUPTED", "导出线程被中断", LocalDateTime.now());
        } catch (Exception exception) {
            mapper.markFailed(exportId, "EXPORT_FAILED",
                    exception.getMessage() == null ? "清洗子集导出失败" : exception.getMessage(),
                    LocalDateTime.now());
        }
    }
}
