package me.zhengjie.modules.system.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.CleanSubsetExportTask;
import me.zhengjie.modules.system.mapper.CleanSubsetExportTaskMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 将应用重启后无法继续执行的导出任务收敛为可解释失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanSubsetExportTaskRecoveryRunner implements ApplicationRunner {

    private final CleanSubsetExportTaskMapper exportTaskMapper;
    private final CleanSubsetExportTaskExecutor exportTaskExecutor;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<CleanSubsetExportTask> runningTasks = exportTaskMapper.findByStatus("running");
            int recovered = 0;
            if (runningTasks != null) {
                for (CleanSubsetExportTask task : runningTasks) {
                    if (exportTaskMapper.failOrphaned(
                            task.getExportId(),
                            "EXPORT_EXECUTOR_RESTARTED",
                            "应用进程重启或异常退出，导出任务已收敛为可重试失败",
                            LocalDateTime.now()) == 1) {
                        recovered++;
                    }
                }
            }
            log.info("Clean subset export recovery completed: scanned={}, recovered={}",
                    runningTasks == null ? 0 : runningTasks.size(), recovered);

            // A pending row can exist when the process exits after the insert
            // succeeds but before the async executor is scheduled.
            List<CleanSubsetExportTask> pendingTasks = exportTaskMapper.findByStatus("pending");
            if (pendingTasks == null || pendingTasks.isEmpty()) {
                return;
            }
            for (CleanSubsetExportTask task : pendingTasks) {
                exportTaskExecutor.execute(task.getExportId());
            }
            log.info("Redispatched pending clean subset exports after startup: count={}",
                    pendingTasks.size());
        } catch (Exception e) {
            // The migration is intentionally separate from the legacy schema.
            // A deployment that has not applied it should still be able to boot.
            log.warn("Skip clean subset export recovery because its table is unavailable: {}",
                    e.getMessage());
        }
    }
}
