package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.CleanSubsetExportTask;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.CleanSubsetExportTaskMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.CleanSubsetExportService;
import me.zhengjie.modules.system.service.CleanSubsetExportTaskService;
import me.zhengjie.modules.system.service.executor.AfterCommitTaskDispatcher;
import me.zhengjie.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 清洗子集导出任务服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanSubsetExportTaskServiceImpl implements CleanSubsetExportTaskService {

    private final CleanSubsetExportTaskMapper exportTaskMapper;
    private final PreDefenseMapper preDefenseMapper;
    private final CleanSubsetExportService cleanSubsetExportService;
    private final CleanSubsetExportTaskExecutor exportTaskExecutor;
    private final AfterCommitTaskDispatcher afterCommitTaskDispatcher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CleanSubsetExportTask submit(PreDefense task, Integer requestedRatio) {
        int ratio = resolveRatio(task, requestedRatio);
        CleanSubsetExportTask existing = exportTaskMapper.findByTaskAndRatio(task.getTaskId(), ratio);
        if (existing != null) {
            return retryIfFailed(existing);
        }

        CleanSubsetExportTask exportTask = new CleanSubsetExportTask();
        exportTask.setTaskId(task.getTaskId());
        exportTask.setIsolationRatio(ratio);
        exportTask.setStatus("pending");
        exportTask.setProgress(0);
        exportTask.setCreateBy(resolveOperator());

        // Let the database unique key arbitrate concurrent submissions. This
        // avoids catching a duplicate-key exception inside the transaction,
        // which can leave the transaction rollback-only on some drivers.
        int inserted = exportTaskMapper.insertIfAbsent(exportTask);
        CleanSubsetExportTask createdOrConcurrent = exportTaskMapper.findByTaskAndRatio(
                task.getTaskId(), ratio);
        if (createdOrConcurrent == null) {
            throw new BadRequestException("导出任务创建失败，请稍后重试");
        }

        CleanSubsetExportTask result = retryIfFailed(createdOrConcurrent);
        if (inserted == 1 && "pending".equals(result.getStatus())) {
            dispatchAfterCommit(result.getExportId());
            log.info("Submitted clean subset export: taskId={}, exportId={}, ratio={}",
                    task.getTaskId(), result.getExportId(), ratio);
        }
        return result;
    }

    /**
     * Controller requests normally provide the authenticated username. The
     * fallback keeps internal service calls and recovery-oriented tests from
     * depending on an HTTP request context.
     */
    private String resolveOperator() {
        try {
            String username = SecurityUtils.getCurrentUsername();
            return StringUtils.hasText(username) ? username : "system";
        } catch (Exception e) {
            log.debug("No authenticated request context for export submission; using system operator");
            return "system";
        }
    }

    private CleanSubsetExportTask retryIfFailed(CleanSubsetExportTask existing) {
        if (!"failed".equals(existing.getStatus())) {
            return existing;
        }
        if (exportTaskMapper.resetForRetry(existing.getExportId()) != 1) {
            return exportTaskMapper.selectById(existing.getExportId());
        }
        CleanSubsetExportTask retried = exportTaskMapper.selectById(existing.getExportId());
        if (retried == null) {
            throw new BadRequestException("导出任务重试失败，请稍后刷新");
        }
        dispatchAfterCommit(retried.getExportId());
        return retried;
    }

    private void dispatchAfterCommit(Long exportId) {
        afterCommitTaskDispatcher.dispatch(
                () -> exportTaskExecutor.execute(exportId),
                error -> markDispatchFailure(exportId, error));
    }

    private void markDispatchFailure(Long exportId, RuntimeException error) {
        String reason = "导出任务执行线程池已满，任务未能提交";
        if (error != null && StringUtils.hasText(error.getMessage())) {
            reason += ": " + error.getMessage();
        }
        int updated = exportTaskMapper.failBeforeExecution(
                exportId,
                "EXPORT_QUEUE_FULL",
                reason,
                LocalDateTime.now());
        if (updated == 1) {
            log.warn("清洗子集导出任务因执行池满而失败: exportId={}", exportId);
        } else {
            log.warn("清洗子集导出派发失败，但任务状态已变化: exportId={}", exportId);
        }
    }

    @Override
    public CleanSubsetExportTask findById(Long exportId) {
        CleanSubsetExportTask exportTask = exportTaskMapper.selectById(exportId);
        if (exportTask == null) {
            throw new BadRequestException("导出任务不存在");
        }
        return exportTask;
    }

    @Override
    public List<CleanSubsetExportTask> findByTaskId(Long taskId) {
        List<CleanSubsetExportTask> tasks = exportTaskMapper.findByTaskId(taskId);
        return tasks == null ? new ArrayList<CleanSubsetExportTask>() : tasks;
    }

    @Override
    public Path resolveDownloadFile(Long exportId) throws IOException {
        CleanSubsetExportTask exportTask = findById(exportId);
        if (!"success".equals(exportTask.getStatus())) {
            throw new BadRequestException("导出任务尚未完成");
        }
        if (!StringUtils.hasText(exportTask.getFilePath())) {
            throw new BadRequestException("导出任务没有生成文件");
        }

        PreDefense task = preDefenseMapper.selectById(exportTask.getTaskId());
        if (task == null || ObjectUtil.isEmpty(task.getCleaningResult())) {
            throw new BadRequestException("原清洗任务不存在或没有结果");
        }
        JSONObject result = JSON.parseObject(task.getCleaningResult());
        JSONObject artifacts = result.getJSONObject("artifacts");
        if (artifacts == null || !StringUtils.hasText(artifacts.getString("path"))) {
            throw new BadRequestException("原清洗任务产物目录不可用");
        }

        Path artifactRoot = Paths.get(artifacts.getString("path")).toAbsolutePath().normalize();
        Path target = Paths.get(exportTask.getFilePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) {
            throw new BadRequestException("导出文件不存在");
        }
        Path realRoot = artifactRoot.toRealPath();
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(realRoot) || !realTarget.getFileName().toString().endsWith(".zip")) {
            throw new BadRequestException("导出文件路径不可访问");
        }
        return realTarget;
    }

    @Override
    public String buildDownloadFileName(CleanSubsetExportTask exportTask) {
        return "task-" + exportTask.getTaskId()
                + "-ldd-clean-subset-" + exportTask.getIsolationRatio() + "percent.zip";
    }

    private int resolveRatio(PreDefense task, Integer requestedRatio) {
        Map<String, Object> description = cleanSubsetExportService.describe(task);
        Object rawAvailable = description.get("available");
        if (!Boolean.TRUE.equals(rawAvailable)) {
            throw new BadRequestException("当前任务没有可导出的 LDD 干净子集");
        }
        @SuppressWarnings("unchecked")
        List<Integer> ratios = (List<Integer>) description.get("isolationRatios");
        if (ratios == null || ratios.isEmpty()) {
            throw new BadRequestException("当前任务没有可导出的 LDD 样本索引");
        }
        int ratio = requestedRatio == null ? ratios.get(0) : requestedRatio;
        if (!ratios.contains(ratio)) {
            throw new BadRequestException("指定隔离比例没有对应的 LDD 样本索引");
        }
        return ratio;
    }
}
