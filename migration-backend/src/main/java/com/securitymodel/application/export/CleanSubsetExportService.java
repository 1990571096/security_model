package com.securitymodel.application.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.task.AfterCommitTaskDispatcher;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final TaskAccessGuard taskAccessGuard;

    public CleanSubsetExportService(
            CleanSubsetExportTaskMapper exportMapper,
            PreDefenseTaskMapper taskMapper,
            ObjectMapper objectMapper,
            AfterCommitTaskDispatcher dispatcher,
            CleanSubsetExportExecutor executor,
            OperationAuditService operationAuditService,
            CurrentUserService currentUserService,
            TaskAccessGuard taskAccessGuard) {
        this.exportMapper = exportMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.operationAuditService = operationAuditService;
        this.currentUserService = currentUserService;
        this.taskAccessGuard = taskAccessGuard;
    }

    public Map<String, Object> describe(PreDefenseTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("selectionKind", null);
        result.put("isolationRatios", List.of());
        result.put("dataset", "");
        result.put("description", "任务成功并生成清洗索引后可导出子集。");
        if (task == null || !"success".equals(task.getStatus()) || !StringUtils.hasText(task.getCleaningResult())) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(task.getCleaningResult());
            result.put("dataset", root.path("effective_config").path("dataset").asText(""));
            JsonNode artifacts = root.path("artifacts");
            result.put("description", "本次运行未生成可导出的保留样本索引。");
            if (!"retained_clean_subset".equals(artifacts.path("selection_kind").asText())) {
                return result;
            }
            if (!supportsExport(root)) {
                result.put("description", "当前子集导出仅支持 CIFAR-10 的 LDD 结果。");
                return result;
            }
            result.put("description", "CIFAR-10 训练批次或本次运行的索引文件缺失或不可读，请检查路径。");
            Path artifactRoot = safeDirectory(artifacts.path("path").asText(), "任务产物目录不可用");
            datasetRoot(root);
            List<Integer> ratios = List.copyOf(selectionFiles(artifacts.path("selection_files"), artifactRoot).keySet());
            result.put("available", !ratios.isEmpty());
            result.put("selectionKind", artifacts.path("selection_kind").asText());
            result.put("isolationRatios", ratios);
            result.put("defaultIsolationRatio", ratios.isEmpty() ? null : ratios.get(0));
            if (!ratios.isEmpty()) {
                result.put("description", "依据 LDD 保留样本索引，从 CIFAR-10 原始训练集重建子集 ZIP。");
            }
        } catch (Exception ignored) {
            // A malformed result is reported as unavailable instead of exposing internal parser details.
        }
        return result;
    }

    /**
     * Business truth for one ratio: index eligibility, whether a new export may be submitted,
     * and whether a previously successful ZIP is still readable. This deliberately separates
     * "sample threshold met" from "can export now" and "can download now".
     */
    public Map<String, Object> availability(PreDefenseTask task, Integer ratio,
                                            CleanSubsetExportTask exportTask) {
        Map<String, Object> description = describe(task);
        @SuppressWarnings("unchecked")
        List<Integer> ratios = (List<Integer>) description.get("isolationRatios");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eligible", false);
        result.put("canSubmit", false);
        result.put("downloadReady", false);
        result.put("exportState", exportTask == null || exportTask.getStatus() == null
                ? "not_requested" : exportTask.getStatus());
        result.put("fileState", "absent");
        result.put("reason", description.get("description"));
        if (ratio == null || ratios == null || !ratios.contains(ratio)) {
            String dataset = String.valueOf(description.get("dataset"));
            result.put("reason", StringUtils.hasText(dataset) && !"cifar10".equalsIgnoreCase(dataset)
                    ? "当前数据集 " + dataset + " 不在导出支持范围（仅 CIFAR-10）"
                    : "该保留比例没有可导出的 CIFAR-10 索引");
            result.put("exportLabel", "不可导出");
            return result;
        }
        result.put("eligible", true);
        result.put("reason", "该比例满足 CIFAR-10 索引和训练批次资格");
        if (exportTask == null || exportTask.getStatus() == null) {
            result.put("canSubmit", true);
            result.put("exportLabel", "尚未导出，可提交生成");
            return result;
        }
        result.put("exportId", exportTask.getExportId().toString());
        result.put("exportProgress", exportTask.getProgress());
        switch (exportTask.getStatus()) {
            case "pending" -> {
                result.put("exportLabel", "导出排队中");
            }
            case "running" -> {
                result.put("exportLabel", "正在生成");
            }
            case "failed" -> {
                result.put("canSubmit", true);
                result.put("exportLabel", "导出失败，可重试");
            }
            case "success" -> {
                Optional<Path> zip = availableZip(task, exportTask);
                if (zip.isPresent()) {
                    result.put("downloadReady", true);
                    result.put("fileState", "readable");
                    result.put("exportLabel", "ZIP 可下载");
                } else {
                    result.put("canSubmit", true);
                    result.put("fileState", "missing_or_invalid");
                    result.put("exportLabel", "导出记录成功但 ZIP 已失效，可重新生成");
                }
            }
            default -> result.put("exportLabel", "导出状态未知");
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public CleanSubsetExportTask submit(Long taskId, Integer requestedRatio) {
        PreDefenseTask task = taskAccessGuard.requireOwnedForUpdate(taskId);
        int ratio = requestedRatio == null ? resolveRatio(task, null) : requestedRatio;
        CleanSubsetExportTask exportTask = exportMapper.findByTaskAndRatio(taskId, ratio);
        String previousStatus = exportTask == null ? null : exportTask.getStatus();
        if ("pending".equals(previousStatus) || "running".equals(previousStatus)
                || ("success".equals(previousStatus) && availableZip(task, exportTask).isPresent())) {
            return exportTask;
        }

        resolveRatio(task, ratio);
        if (exportTask == null) {
            exportMapper.insertIfAbsent(taskId, ratio, currentUserService.username());
            exportTask = exportMapper.findByTaskAndRatio(taskId, ratio);
            if (exportTask == null) {
                throw new IllegalStateException("导出任务创建失败");
            }
        } else {
            Long exportId = exportTask.getExportId();
            if (exportMapper.resetForRetry(exportId, previousStatus) != 1) {
                return exportMapper.selectById(exportId);
            }
            exportTask = exportMapper.selectById(exportId);
        }
        operationAuditService.recordExport(
                exportTask.getExportId(), previousStatus == null ? "EXPORT_SUBMIT" : "EXPORT_RETRY",
                previousStatus, "pending", Map.of("taskId", taskId, "isolationRatio", ratio),
                previousStatus == null ? "提交清洗子集导出" : "重试清洗子集导出");
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

    /** Internal lookup used by the async executor (no security context). Never expose through a controller. */
    public CleanSubsetExportTask findRequired(Long exportId) {
        CleanSubsetExportTask task = exportMapper.selectById(exportId);
        if (task == null) {
            throw new IllegalArgumentException("导出任务不存在");
        }
        return task;
    }

    /** Controller-facing lookup: requires read permission on the parent task. */
    public CleanSubsetExportTask findReadable(Long exportId) {
        CleanSubsetExportTask task = findRequired(exportId);
        taskAccessGuard.requireReadable(task.getTaskId());
        return task;
    }

    public List<CleanSubsetExportTask> findByTaskId(Long taskId) {
        taskAccessGuard.requireReadable(taskId);
        return exportMapper.findByTaskId(taskId);
    }

    public Path resolveDownloadFile(Long exportId) {
        CleanSubsetExportTask exportTask = findReadable(exportId);
        if (!"success".equals(exportTask.getStatus())) {
            throw new IllegalArgumentException("导出任务尚未完成");
        }
        return availableZip(requiredTask(exportTask.getTaskId()), exportTask)
                .orElseThrow(ExportFileUnavailableException::new);
    }

    private Optional<Path> availableZip(PreDefenseTask task, CleanSubsetExportTask exportTask) {
        if (!StringUtils.hasText(exportTask.getFilePath())) {
            return Optional.empty();
        }
        try {
            Path root = artifactRoot(task);
            Path target = Path.of(exportTask.getFilePath()).toRealPath();
            if (target.startsWith(root) && target.getFileName().toString().endsWith(".zip")
                    && Files.isRegularFile(target) && Files.isReadable(target) && Files.size(target) > 0) {
                return Optional.of(target);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // The owner can request a rebuild if the original input files are still available.
        }
        return Optional.empty();
    }

    public static class ExportFileUnavailableException extends RuntimeException {
        public ExportFileUnavailableException() {
            super("导出文件已失效。任务所有者可以重新生成子集。");
        }
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
            if (!supportsExport(root)
                    || !"retained_clean_subset".equals(artifacts.path("selection_kind").asText())) {
                throw new IllegalArgumentException("当前任务不包含可导出的 CIFAR-10 LDD 清洗索引");
            }
            Path artifactRoot = safeDirectory(artifacts.path("path").asText(), "任务产物目录不可用");
            Path datasetRoot = datasetRoot(root);
            Path selection = selectionFiles(artifacts.path("selection_files"), artifactRoot)
                    .get(exportTask.getIsolationRatio());
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
        if (!StringUtils.hasText(task.getCleaningResult())) {
            throw new IllegalArgumentException("任务结果不存在");
        }
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
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(path) || !Files.isReadable(path)) {
                throw new IllegalArgumentException(message);
            }
            return path.toRealPath();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private boolean supportsExport(JsonNode result) {
        return "vab://pre-defense/ldd".equals(result.path("algorithm").asText())
                && "cifar10".equalsIgnoreCase(result.path("effective_config").path("dataset").asText());
    }

    private Path datasetRoot(JsonNode result) {
        Path root = safeDirectory(result.path("effective_config").path("data_path").asText(), "数据集目录不可用");
        Path batches = root.resolve("cifar-10-batches-py");
        for (int number = 1; number <= 5; number++) {
            Path batch = batches.resolve("data_batch_" + number);
            try {
                if (!Files.isRegularFile(batch) || !Files.isReadable(batch) || Files.size(batch) == 0) {
                    throw new IllegalArgumentException("CIFAR-10 训练批次缺失或不可读: " + batch.getFileName());
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("CIFAR-10 训练批次不可读: " + batch.getFileName(), exception);
            }
        }
        return root;
    }

    private Map<Integer, Path> selectionFiles(JsonNode files, Path artifactRoot) {
        Map<Integer, Path> selections = new TreeMap<>();
        if (!files.isArray()) return selections;
        for (JsonNode file : files) {
            if (!file.isTextual() || file.asText().isBlank()) continue;
            try {
                Path candidate = Path.of(file.asText()).toRealPath();
                if (!candidate.startsWith(artifactRoot) || !Files.isRegularFile(candidate)
                        || !Files.isReadable(candidate) || Files.size(candidate) == 0) continue;
                Matcher matcher = SELECTION_RATIO.matcher(candidate.getFileName().toString());
                if (matcher.matches()) {
                    selections.putIfAbsent(Integer.parseInt(matcher.group(1)), candidate);
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // Other ratios can still be exported if this index has moved or is invalid.
            }
        }
        return selections;
    }

    public record ExportContext(PreDefenseTask task, CleanSubsetExportTask exportTask,
                                Path artifactRoot, Path datasetRoot, Path selectionFile) {
    }
}
