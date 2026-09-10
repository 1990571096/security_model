package me.zhengjie.modules.system.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.CleanSubsetExportTask;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.CleanSubsetExportTaskMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.CleanSubsetExportService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 异步执行清洗子集打包。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanSubsetExportTaskExecutor {

    private final CleanSubsetExportTaskMapper exportTaskMapper;
    private final PreDefenseMapper preDefenseMapper;
    private final CleanSubsetExportService cleanSubsetExportService;

    @Async("taskAsync")
    public void execute(Long exportId) {
        LocalDateTime startTime = LocalDateTime.now();
        if (exportTaskMapper.claimForExecution(exportId, startTime) != 1) {
            log.info("Skip non-pending clean subset export task: exportId={}", exportId);
            return;
        }

        CleanSubsetExportTask exportTask = exportTaskMapper.selectById(exportId);
        try {
            if (exportTask == null) {
                throw new IllegalStateException("EXPORT_TASK_NOT_FOUND: 导出任务不存在");
            }
            PreDefense task = preDefenseMapper.selectById(exportTask.getTaskId());
            if (task == null) {
                throw new IllegalStateException("SOURCE_TASK_NOT_FOUND: 清洗任务不存在");
            }

            exportTaskMapper.updateProgress(exportId, 25);
            Path zipFile = cleanSubsetExportService.export(task, exportTask.getIsolationRatio());
            exportTaskMapper.updateProgress(exportId, 90);

            String metadataPath = zipFile.resolveSibling(
                    zipFile.getFileName().toString().replace(".zip", ".metadata.json")).toString();
            if (exportTaskMapper.markSuccess(
                    exportId, zipFile.toAbsolutePath().toString(), metadataPath, LocalDateTime.now()) != 1) {
                log.warn("Ignored stale clean subset export success callback: exportId={}", exportId);
            }
        } catch (Exception e) {
            String errorCode = classify(e);
            String reason = cleanReason(e);
            if (exportTaskMapper.markFailed(exportId, errorCode, reason, LocalDateTime.now()) != 1) {
                log.warn("Ignored stale clean subset export failure callback: exportId={}", exportId);
            }
            log.error("Clean subset export failed: exportId={}, errorCode={}", exportId, errorCode, e);
        }
    }

    private String classify(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message != null) {
            int separator = message.indexOf(':');
            if (separator > 0) {
                String candidate = message.substring(0, separator).trim();
                if (candidate.matches("[A-Z][A-Z0-9_]+")) {
                    return candidate;
                }
            }
        }
        return "EXPORT_EXECUTION_ERROR";
    }

    private String cleanReason(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "清洗数据集导出失败";
        }
        int separator = message.indexOf(':');
        return separator > 0 ? message.substring(separator + 1).trim() : message;
    }
}
