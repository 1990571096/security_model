package com.securitymodel.application.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.task.AfterCommitTaskDispatcher;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CleanSubsetExportService {

    private static final Pattern SELECTION_RATIO = Pattern.compile("selected_indices_(\\d+)\\.npy$");
    private final CleanSubsetExportTaskMapper exportMapper;
    private final PreDefenseTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final AfterCommitTaskDispatcher dispatcher;
    private final CleanSubsetExportExecutor executor;
    private final OperationAuditService operationAuditService;
    private final CurrentUserService currentUserService;

    public CleanSubsetExportService(
            CleanSubsetExportTaskMapper exportMapper,
            PreDefenseTaskMapper taskMapper,
            ObjectMapper objectMapper,
            AfterCommitTaskDispatcher dispatcher,
            CleanSubsetExportExecutor executor,
            OperationAuditService operationAuditService,
            CurrentUserService currentUserService) {
        this.exportMapper = exportMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.operationAuditService = operationAuditService;
        this.currentUserService = currentUserService;
    }

    public Map<String, Object> describe(PreDefenseTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("selectionKind", null);
        result.put("isolationRatios", List.of());
        if (task == null || !"success".equals(task.getStatus()) || !StringUtils.hasText(task.getCleaningResult())) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(task.getCleaningResult());
            JsonNode artifacts = root.path("artifacts");
            if (!"retained_clean_subset".equals(artifacts.path("selection_kind").asText())) {
                return result;
            }
            List<Integer> ratios = selectionRatios(artifacts.path("selection_files"));
            result.put("available", !ratios.isEmpty());
            result.put("selectionKind", artifacts.path("selection_kind").asText());
            result.put("isolationRatios", ratios);
            result.put("defaultIsolationRatio", ratios.isEmpty() ? null : ratios.get(0));
            result.put("description", "依据 LDD 保留样本索引，从 CIFAR-10 原始训练集重建干净子集 ZIP。");
        } catch (Exception ignored) {
            // A malformed result is reported as unavailable instead of exposing internal parser details.
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public CleanSubsetExportTask submit(Long taskId, Integer requestedRatio) {
        PreDefenseTask task = requiredTask(taskId);
        int ratio = resolveRatio(task, requestedRatio);
        String operator = currentUserService.username();
        exportMapper.insertIfAbsent(taskId, ratio, operator);
        CleanSubsetExportTask exportTask = exportMapper.findByTaskAndRatio(taskId, ratio);
        if (exportTask == null) {
            throw new IllegalStateException("导出任务创建失败");
        }
        if ("failed".equals(exportTask.getStatus())
                && exportMapper.resetForRetry(exportTask.getExportId()) == 1) {
            operationAuditService.recordExport(
                    exportTask.getExportId(), "EXPORT_RETRY", "failed", "pending",
                    Map.of("taskId", taskId, "isolationRatio", ratio), "重试清洗子集导出");
            exportTask = exportMapper.selectById(exportTask.getExportId());
        }
        if ("pending".equals(exportTask.getStatus())
                && exportTask.getStartTime() == null) {
            operationAuditService.recordExport(
                    exportTask.getExportId(), "EXPORT_SUBMIT", null, "pending",
                    Map.of("taskId", taskId, "isolationRatio", ratio), "提交清洗子集导出");
        }
        if ("pending".equals(exportTask.getStatus())) {
            CleanSubsetExportTask finalTask = exportTask;
            dispatcher.dispatch(
                    () -> executor.execute(finalTask.getExportId()),
                    error -> exportMapper.failBeforeExecution(finalTask.getExportId(),
                            "EXPORT_QUEUE_FULL", "导出任务未能提交执行: " + error.getMessage(),
                            LocalDateTime.now()));
        }
        return exportTask;
    }

    public CleanSubsetExportTask findRequired(Long exportId) {
        CleanSubsetExportTask task = exportMapper.selectById(exportId);
        if (task == null) {
            throw new IllegalArgumentException("导出任务不存在");
        }
        return task;
    }

    public List<CleanSubsetExportTask> findByTaskId(Long taskId) {
        requiredTask(taskId);
        return exportMapper.findByTaskId(taskId);
    }

    public Path resolveDownloadFile(Long exportId) throws IOException {
        CleanSubsetExportTask exportTask = findRequired(exportId);
        if (!"success".equals(exportTask.getStatus()) || !StringUtils.hasText(exportTask.getFilePath())) {
            throw new IllegalArgumentException("导出任务尚未完成");
        }
        PreDefenseTask task = requiredTask(exportTask.getTaskId());
        Path artifactRoot = artifactRoot(task);
        Path target = Paths.get(exportTask.getFilePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("导出文件不存在");
        }
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(artifactRoot) || !realTarget.getFileName().toString().endsWith(".zip")) {
            throw new IllegalArgumentException("导出文件路径不可访问");
        }
        return realTarget;
    }

    public String buildDownloadFileName(CleanSubsetExportTask task) {
        return "task-" + task.getTaskId() + "-ldd-clean-subset-" + task.getIsolationRatio() + "percent.zip";
    }

    ExportContext resolveContext(Long exportId) {
        CleanSubsetExportTask exportTask = findRequired(exportId);
        PreDefenseTask task = requiredTask(exportTask.getTaskId());
        try {
            JsonNode root = objectMapper.readTree(task.getCleaningResult());
            JsonNode artifacts = root.path("artifacts");
            JsonNode config = root.path("effective_config");
            if (!"vab://pre-defense/ldd".equals(root.path("algorithm").asText())
                    || !"retained_clean_subset".equals(artifacts.path("selection_kind").asText())
                    || !"cifar10".equalsIgnoreCase(config.path("dataset").asText())) {
                throw new IllegalArgumentException("当前任务不包含可导出的 CIFAR-10 LDD 清洗索引");
            }
            Path artifactRoot = artifactRoot(task);
            Path datasetRoot = safeDirectory(config.path("data_path").asText(), "数据集目录不可用");
            Path selection = null;
            for (JsonNode raw : artifacts.path("selection_files")) {
                Path candidate = Paths.get(raw.asText()).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate) && candidate.getFileName() != null
                        && SELECTION_RATIO.matcher(candidate.getFileName().toString()).matches()
                        && candidate.toRealPath().startsWith(artifactRoot)) {
                    int ratio = ratio(candidate);
                    if (ratio == exportTask.getIsolationRatio()) {
                        selection = candidate.toRealPath();
                        break;
                    }
                }
            }
            if (selection == null) {
                throw new IllegalArgumentException("没有找到对应隔离比例的 LDD 样本索引");
            }
            return new ExportContext(task, exportTask, artifactRoot, datasetRoot, selection);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException("清洗子集导出上下文不可用", exception);
        }
    }

    private PreDefenseTask requiredTask(Long taskId) {
        PreDefenseTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        return task;
    }

    private int resolveRatio(PreDefenseTask task, Integer requestedRatio) {
        @SuppressWarnings("unchecked")
        List<Integer> ratios = (List<Integer>) describe(task).get("isolationRatios");
        if (ratios == null || ratios.isEmpty()) {
            throw new IllegalArgumentException("当前任务没有可导出的 LDD 干净子集");
        }
        int ratio = requestedRatio == null ? ratios.get(0) : requestedRatio;
        if (!ratios.contains(ratio)) {
            throw new IllegalArgumentException("指定隔离比例没有对应的 LDD 样本索引");
        }
        return ratio;
    }

    private Path artifactRoot(PreDefenseTask task) {
        try {
            JsonNode root = objectMapper.readTree(task.getCleaningResult());
            return safeDirectory(root.path("artifacts").path("path").asText(), "任务产物目录不可用");
        } catch (IOException exception) {
            throw new IllegalArgumentException("任务结果格式错误");
        }
    }

    private Path safeDirectory(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        try {
            Path path = Paths.get(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException(message);
            }
            return path.toRealPath();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private List<Integer> selectionRatios(JsonNode files) {
        List<Integer> ratios = new ArrayList<>();
        if (files.isArray()) {
            files.forEach(file -> {
                Matcher matcher = SELECTION_RATIO.matcher(Paths.get(file.asText()).getFileName().toString());
                if (matcher.matches()) {
                    ratios.add(Integer.parseInt(matcher.group(1)));
                }
            });
        }
        ratios.sort(Integer::compareTo);
        return ratios;
    }

    private int ratio(Path path) {
        Matcher matcher = SELECTION_RATIO.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    public record ExportContext(PreDefenseTask task, CleanSubsetExportTask exportTask,
                                Path artifactRoot, Path datasetRoot, Path selectionFile) {
    }
}
