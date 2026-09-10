package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.exception.EntityExistException;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.Hyper;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.domain.dto.PreDefenseQueryCriteria;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.AlgorithmService;
import me.zhengjie.modules.system.service.DatasetService;
import me.zhengjie.modules.system.service.HyperService;
import me.zhengjie.modules.system.service.PreDefenseService;
import me.zhengjie.modules.system.service.executor.AfterCommitTaskDispatcher;
import me.zhengjie.modules.system.service.executor.DefenseTaskExecutor;
import me.zhengjie.modules.system.service.executor.dto.DefenseResourceRef;
import me.zhengjie.modules.system.service.executor.dto.DefenseTaskRequest;
import me.zhengjie.modules.system.service.policy.LddConfigurationPolicy;
import me.zhengjie.modules.system.service.policy.TaskExecutionQuotaPolicy;
import me.zhengjie.modules.system.service.snapshot.ExecutionFingerprintService;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 训练前防御任务服务实现
 * @author ZJING
 * @date 2025-07-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreDefenseServiceImpl extends ServiceImpl<PreDefenseMapper, PreDefense> implements PreDefenseService {

    private final PreDefenseMapper preDefenseMapper;
    private final DefenseTaskExecutor defenseTaskExecutor;
    private final DatasetService datasetService;
    private final AlgorithmService algorithmService;
    private final HyperService hyperService;
    private final LddConfigurationPolicy lddConfigurationPolicy;
    private final TaskExecutionQuotaPolicy taskExecutionQuotaPolicy;
    private final ExecutionFingerprintService executionFingerprintService;
    private final AfterCommitTaskDispatcher afterCommitTaskDispatcher;

    @Override
    public PreDefense findById(Long taskId) {
        PreDefense task = preDefenseMapper.selectById(taskId);
        if (ObjectUtil.isNull(task)) {
            throw new BadRequestException("任务不存在");
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(PreDefense resources) {
        // 检查任务名称是否已存在
        if (preDefenseMapper.findByTaskName(resources.getTaskName()) != null) {
            throw new EntityExistException(PreDefense.class, "taskName", resources.getTaskName());
        }
        
        // 验证任务参数
        validateTask(resources);
        resources.setCustomParams(lddConfigurationPolicy.normalizeObject(
                resources.getCustomParams(), "自定义参数"));
        
        // 设置默认值
        if (ObjectUtil.isEmpty(resources.getStatus())) {
            resources.setStatus("pending");
        }
        if (ObjectUtil.isNull(resources.getProgress())) {
            resources.setProgress(0);
        }
        
        preDefenseMapper.insert(resources);
        log.info("用户 {} 创建了新的训练前防御任务: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTaskName(),
                resources.getTaskId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(PreDefense resources) {
        PreDefense task = findById(resources.getTaskId());
        if (!"pending".equals(task.getStatus())) {
            throw new BadRequestException("只有等待中的任务才能编辑");
        }

        // 检查任务名称是否已被其他任务使用
        PreDefense taskByName = preDefenseMapper.findByTaskName(resources.getTaskName());
        if (taskByName != null && !taskByName.getTaskId().equals(resources.getTaskId())) {
            throw new EntityExistException(PreDefense.class, "taskName", resources.getTaskName());
        }

        // 验证任务参数
        validateTask(resources);
        resources.setCustomParams(lddConfigurationPolicy.normalizeObject(
                resources.getCustomParams(), "自定义参数"));

        preDefenseMapper.updateById(resources);
        log.info("用户 {} 更新了训练前防御任务: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTaskName(),
                resources.getTaskId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Set<Long> taskIds) {
        String currentUser = SecurityUtils.getCurrentUsername();
        
        for (Long taskId : taskIds) {
            PreDefense task = findById(taskId);
            
            // 权限检查：只能删除自己创建的任务
            if (!ObjectUtil.equal(task.getCreateBy(), currentUser)) {
                throw new BadRequestException("只能删除自己创建的任务【" + task.getTaskName() + "】");
            }
            
            // 业务检查：运行中的任务不能删除
            if ("running".equals(task.getStatus())) {
                throw new BadRequestException("任务【" + task.getTaskName() + "】正在运行中，无法删除");
            }
        }

        preDefenseMapper.deleteBatchIds(taskIds);
        log.info("用户 {} 删除了 {} 个训练前防御任务", currentUser, taskIds.size());
    }

    @Override
    public List<PreDefense> findByDatasetId(Long datasetId) {
        return preDefenseMapper.findByDatasetId(datasetId);
    }

    @Override
    public List<PreDefense> findByAlgorithmId(Long algorithmId) {
        return preDefenseMapper.findByAlgorithmId(algorithmId);
    }

    @Override
    public Map<String, Object> getCleaningEffectStatistics() {
        try {
            Map<String, Object> stats = preDefenseMapper.getCleaningEffectStatistics();
            if (stats == null) {
                stats = new HashMap<>();
                stats.put("total_tasks", 0L);
                stats.put("success_tasks", 0L);
                stats.put("failed_tasks", 0L);
                stats.put("canceled_tasks", 0L);
                stats.put("running_tasks", 0L);
                stats.put("pending_tasks", 0L);
                stats.put("avg_progress", 0.0);
                stats.put("avg_duration_minutes", 0.0);
            }

            // 计算成功率
            Number totalTasks = asNumber(stats.get("total_tasks"));
            Number successTasks = asNumber(stats.get("success_tasks"));
            if (totalTasks != null && totalTasks.doubleValue() > 0 && successTasks != null) {
                double successRate = (successTasks.doubleValue() / totalTasks.doubleValue()) * 100;
                stats.put("success_rate", Math.round(successRate * 100.0) / 100.0);
            } else {
                stats.put("success_rate", 0.0);
            }

            return stats;
        } catch (Exception e) {
            log.error("获取清洗效果统计失败", e);
            // 返回默认统计数据
            Map<String, Object> defaultStats = new HashMap<>();
            defaultStats.put("total_tasks", 0L);
            defaultStats.put("success_tasks", 0L);
            defaultStats.put("failed_tasks", 0L);
            defaultStats.put("canceled_tasks", 0L);
            defaultStats.put("running_tasks", 0L);
            defaultStats.put("pending_tasks", 0L);
            defaultStats.put("avg_progress", 0.0);
            defaultStats.put("avg_duration_minutes", 0.0);
            defaultStats.put("success_rate", 0.0);
            return defaultStats;
        }
    }

    @Override
    public List<Map<String, Object>> getAlgorithmUsageStatistics() {
        try {
            List<Map<String, Object>> stats = preDefenseMapper.getAlgorithmUsageStatistics();
            return stats != null ? stats : new ArrayList<>();
        } catch (Exception e) {
            log.error("获取算法使用统计失败", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<Map<String, Object>> getDatasetUsageStatistics() {
        try {
            List<Map<String, Object>> stats = preDefenseMapper.getDatasetUsageStatistics();
            return stats != null ? stats : new ArrayList<>();
        } catch (Exception e) {
            log.error("获取数据集使用统计失败", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<Map<String, Object>> getTimeTrendStatistics() {
        try {
            List<Map<String, Object>> stats = preDefenseMapper.getTimeTrendStatistics();
            return stats != null ? stats : new ArrayList<>();
        } catch (Exception e) {
            log.error("获取时间趋势统计失败", e);
            return new ArrayList<>();
        }
    }

    @Override
    public Map<String, Object> getExecutionLogs(Long taskId) {
        return getExecutionLogs(taskId, Collections.emptyMap());
    }

    @Override
    public Map<String, Object> getExecutionLogs(Long taskId, Map<String, Long> offsets) {
        PreDefense task = findById(taskId);
        Map<String, Long> safeOffsets = offsets == null ? Collections.emptyMap() : offsets;
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> logs = new ArrayList<>();
        response.put("taskId", taskId);
        response.put("available", false);
        response.put("logs", logs);
        response.put("incremental", !safeOffsets.isEmpty());

        if (ObjectUtil.isEmpty(task.getCleaningResult())) {
            if (appendRunningExecutionLogs(task, logs, safeOffsets)) {
                response.put("available", true);
                response.put("message", safeOffsets.isEmpty()
                        ? "正在读取运行中的执行日志"
                        : "正在增量读取运行中的执行日志");
            } else {
                response.put("message", "任务尚未生成执行结果");
            }
            return response;
        }

        try {
            JSONObject result = JSON.parseObject(task.getCleaningResult());
            JSONObject artifacts = result.getJSONObject("artifacts");
            if (artifacts == null) {
                response.put("message", appendRunningExecutionLogs(task, logs, safeOffsets)
                        ? "正在读取运行中的执行日志" : "任务结果未登记执行日志");
                response.put("available", !logs.isEmpty());
                return response;
            }

            Object rawLogs = artifacts.get("logs");
            if (!(rawLogs instanceof Collection)) {
                response.put("message", appendRunningExecutionLogs(task, logs, safeOffsets)
                        ? "正在读取运行中的执行日志" : "任务结果未登记执行日志");
                response.put("available", !logs.isEmpty());
                return response;
            }

            Set<String> uniquePaths = new LinkedHashSet<>();
            for (Object rawPath : (Collection<?>) rawLogs) {
                if (rawPath != null && !ObjectUtil.isEmpty(rawPath.toString())) {
                    uniquePaths.add(rawPath.toString());
                }
            }

            for (String rawPath : uniquePaths) {
                Path path;
                try {
                    path = Paths.get(rawPath).toAbsolutePath().normalize();
                } catch (Exception ignored) {
                    continue;
                }
                if (!isWithinArtifactRoot(path, artifacts.getString("path"))
                        || !Files.isRegularFile(path) || !isReadableLogFile(path)) {
                    continue;
                }

                String relativePath = toRelativeArtifactPath(path, artifacts.getString("path"));
                appendLogFile(path, artifacts.getString("path"),
                        resolveLogOffset(safeOffsets, relativePath, path.getFileName().toString()), logs);
            }

            response.put("available", !logs.isEmpty());
            response.put("message", logs.isEmpty()
                    ? "暂未找到可读取的执行日志"
                    : (safeOffsets.isEmpty() ? "执行日志读取成功" : "执行日志增量读取成功"));
        } catch (Exception e) {
            log.warn("读取任务 {} 执行日志失败: {}", taskId, e.getMessage());
            response.put("message", "执行日志读取失败");
        }
        return response;
    }

    /**
     * 运行中的任务尚未写入 result.json，按执行器约定的任务目录读取当前日志。
     * 路径仍由后端拼接，并经过目录 containment 校验，不接受前端传入路径。
     */
    private boolean appendRunningExecutionLogs(
            PreDefense task,
            List<Map<String, Object>> logs,
            Map<String, Long> offsets) {
        if (!"running".equals(task.getStatus()) || task.getCurrentAttempt() == null) {
            return false;
        }
        try {
            Path root = Paths.get(
                    executionFingerprintService.getExecutorWorkspace(),
                    "pre_defense-" + task.getTaskId(),
                    "attempt-" + task.getCurrentAttempt())
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isDirectory(root)) {
                return false;
            }
            boolean appended = false;
            for (String fileName : Arrays.asList("stdout.log", "stderr.log")) {
                appended = appendLogFile(
                        root.resolve(fileName),
                        root.toString(),
                        resolveLogOffset(offsets, fileName, fileName),
                        logs) || appended;
            }
            return appended;
        } catch (Exception e) {
            log.debug("读取运行中任务 {} 的实时日志失败: {}", task.getTaskId(), e.getMessage());
            return false;
        }
    }

    private boolean appendLogFile(
            Path path,
            String artifactRoot,
            Long requestedOffset,
            List<Map<String, Object>> logs)
            throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!isWithinArtifactRoot(normalized, artifactRoot)
                || !Files.isRegularFile(normalized)
                || !isReadableLogFile(normalized)) {
            return false;
        }

        long fileSize = Files.size(normalized);
        long safeRequestedOffset = requestedOffset == null ? 0L : Math.max(0L, requestedOffset);
        boolean reset = safeRequestedOffset > fileSize;
        long startOffset = reset ? 0L : safeRequestedOffset;
        int length = (int) Math.min(fileSize - startOffset, 256 * 1024);
        byte[] bytes = new byte[length];
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(normalized.toFile(), "r")) {
            input.seek(startOffset);
            int offset = 0;
            while (offset < length) {
                int read = input.read(bytes, offset, length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset < length) {
                bytes = Arrays.copyOf(bytes, offset);
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", normalized.getFileName().toString());
        item.put("path", normalized.toString());
        item.put("relativePath", toRelativeArtifactPath(normalized, artifactRoot));
        item.put("content", new String(bytes, StandardCharsets.UTF_8));
        item.put("size", fileSize);
        item.put("startOffset", startOffset);
        item.put("offset", startOffset + bytes.length);
        item.put("reset", reset);
        item.put("truncated", startOffset + bytes.length < fileSize);
        logs.add(item);
        return true;
    }

    private Long resolveLogOffset(Map<String, Long> offsets, String relativePath, String fileName) {
        if (offsets == null || offsets.isEmpty()) {
            return 0L;
        }
        Long offset = offsets.get(relativePath);
        if (offset == null) {
            offset = offsets.get(fileName);
        }
        return offset == null ? 0L : offset;
    }

    private boolean isReadableLogFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".log") || fileName.endsWith(".txt");
    }

    private boolean isWithinArtifactRoot(Path target, String artifactRootValue) {
        if (ObjectUtil.isEmpty(artifactRootValue)) {
            return false;
        }
        try {
            Path root = Paths.get(artifactRootValue).toAbsolutePath().normalize();
            if (!Files.isDirectory(root) || !Files.isRegularFile(target)) {
                return false;
            }
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            return realTarget.startsWith(realRoot);
        } catch (Exception e) {
            return false;
        }
    }

    private String toRelativeArtifactPath(Path target, String artifactRootValue) {
        try {
            Path root = Paths.get(artifactRootValue).toAbsolutePath().normalize();
            return root.relativize(target.toAbsolutePath().normalize()).toString();
        } catch (Exception e) {
            return target.getFileName().toString();
        }
    }

    @Override
    public PreDefense findByTaskName(String taskName) {
        return preDefenseMapper.findByTaskName(taskName);
    }

    @Override
    public PageResult<PreDefense> queryAll(PreDefenseQueryCriteria criteria, Page<Object> page) {
        criteria.setOffset(page.offset());
        IPage<PreDefense> taskPage = preDefenseMapper.findAll(criteria, page);
        return PageUtil.toPage(taskPage);
    }

    @Override
    public List<PreDefense> queryAll(PreDefenseQueryCriteria criteria) {
        return preDefenseMapper.findAll(criteria);
    }

    @Override
    public void download(List<PreDefense> tasks, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PreDefense task : tasks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("任务ID", task.getTaskId());
            map.put("任务名称", task.getTaskName());
            map.put("任务描述", task.getDescription());
            map.put("任务状态", task.getStatus());
            map.put("数据集ID", task.getDatasetId());
            map.put("算法ID", task.getAlgorithmId());
            map.put("进度", task.getProgress() + "%");
            map.put("开始时间", task.getStartTime());
            map.put("结束时间", task.getEndTime());
            map.put("创建时间", task.getCreateTime());
            list.add(map);
        }
        FileUtil.downloadExcel(list, response);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startTask(Long taskId) {
        PreDefense task = findById(taskId);
        if (!"pending".equals(task.getStatus())) {
            throw new BadRequestException("只有等待中的任务才能启动");
        }
        launchTask(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryTask(Long taskId) {
        PreDefense task = findById(taskId);
        if (!"failed".equals(task.getStatus())) {
            throw new BadRequestException("只有失败的任务才能重试");
        }
        if (preDefenseMapper.resetForRetry(taskId) != 1) {
            throw new BadRequestException("任务状态已变化，请刷新后重试");
        }
        task = findById(taskId);
        launchTask(task);
        log.info("重试训练前防御任务: {} (ID: {})", task.getTaskName(), taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelTask(Long taskId) {
        PreDefense task = findById(taskId);
        if (!"running".equals(task.getStatus())) {
            throw new BadRequestException("只有运行中的任务才能取消");
        }
        if (ObjectUtil.isEmpty(task.getCurrentRunId())) {
            throw new BadRequestException("当前任务缺少运行标识，无法安全取消");
        }

        LocalDateTime endTime = LocalDateTime.now();
        String canceledResult = buildCanceledResult(task, endTime);
        int updated = preDefenseMapper.cancelForRun(
                taskId, task.getCurrentRunId(), canceledResult, endTime);
        if (updated != 1) {
            throw new BadRequestException("任务状态已变化，请刷新后重试");
        }

        dispatchCancelAfterCommit(taskId, task.getCurrentRunId());
    }

    private void launchTask(PreDefense task) {
        validateTask(task);
        JSONObject quotaSnapshot = taskExecutionQuotaPolicy.validateAndBuildSnapshot(task);
        DefenseTaskRequest request = buildExecutionRequest(task);
        LocalDateTime launchedAt = LocalDateTime.now();
        String executionSnapshot = appendExecutionSnapshot(
                task.getExecutionSnapshot(), request, launchedAt, quotaSnapshot);
        int claimed = preDefenseMapper.claimForExecution(
                task.getTaskId(),
                request.getRunId(),
                request.getAttempt(),
                launchedAt,
                executionSnapshot);
        if (claimed != 1) {
            throw new BadRequestException("任务状态已变化，请刷新后重试");
        }
        dispatchAfterCommit(request);

        log.info("启动训练前防御任务: {} (ID: {})", task.getTaskName(), task.getTaskId());
    }

    /**
     * Dispatch the external process only after the claim and snapshot commit.
     */
    private void dispatchAfterCommit(DefenseTaskRequest request) {
        afterCommitTaskDispatcher.dispatch(
                () -> defenseTaskExecutor.runPreDefense(request),
                error -> markDispatchFailure(request, error));
    }

    private void markDispatchFailure(DefenseTaskRequest request, RuntimeException error) {
        LocalDateTime finishedAt = LocalDateTime.now();
        PreDefense task = preDefenseMapper.selectById(request.getTaskId());
        JSONObject result = new JSONObject();
        result.put("executor", "taskAsync");
        result.put("stage", "dispatch");
        result.put("status", "failed");
        result.put("run_id", request.getRunId());
        result.put("attempt", request.getAttempt());
        result.put("error_code", "TASK_QUEUE_FULL");
        result.put("failure_reason", "任务执行线程池已满，任务未能提交: " + error.getMessage());
        result.put("started_at", task == null || task.getStartTime() == null
                ? null : task.getStartTime().toString());
        result.put("finished_at", finishedAt.toString());
        result.put("artifacts", new LinkedHashMap<String, Object>());
        int updated = preDefenseMapper.failBeforeExecution(
                request.getTaskId(), request.getRunId(), request.getAttempt(),
                result.toJSONString(), finishedAt);
        if (updated == 1) {
            log.warn("任务因执行池满而失败: taskId={}, runId={}, attempt={}",
                    request.getTaskId(), request.getRunId(), request.getAttempt());
        } else {
            log.warn("任务执行派发失败，但状态已变化: taskId={}, runId={}, attempt={}",
                    request.getTaskId(), request.getRunId(), request.getAttempt());
        }
    }

    /**
     * 先提交取消状态，再终止外部进程，避免事务回滚后数据库状态与运行时状态不一致。
     */
    private void dispatchCancelAfterCommit(Long taskId, String runId) {
        afterCommitTaskDispatcher.dispatch(() -> cancelExternalProcess(taskId, runId));
    }

    private void cancelExternalProcess(Long taskId, String runId) {
        boolean processFound = defenseTaskExecutor.cancelPreDefense(taskId, runId);
        log.info("取消训练前防御任务: taskId={}, runId={}, processFound={}",
                taskId, runId, processFound);
    }

    @Override
    public Integer getTaskProgress(Long taskId) {
        PreDefense task = findById(taskId);
        return task.getProgress();
    }

    @Override
    public List<PreDefense> findByStatus(String status) {
        return preDefenseMapper.findByStatus(status);
    }

    @Override
    public void validateTask(PreDefense resources) {
        // 基础参数验证
        if (ObjectUtil.isEmpty(resources.getTaskName())) {
            throw new BadRequestException("任务名称不能为空");
        }
        if (ObjectUtil.isNull(resources.getDatasetId())) {
            throw new BadRequestException("数据集ID不能为空");
        }
        if (ObjectUtil.isNull(resources.getAlgorithmId())) {
            throw new BadRequestException("算法ID不能为空");
        }

        Dataset dataset = datasetService.findById(resources.getDatasetId());
        if (dataset == null || !Boolean.TRUE.equals(dataset.getEnabled()) || !"success".equals(dataset.getParseStatus())) {
            throw new BadRequestException("指定的数据集不存在或未启用/未解析成功");
        }

        Algorithm algorithm = algorithmService.findById(resources.getAlgorithmId());
        if (algorithm == null || !Boolean.TRUE.equals(algorithm.getEnabled()) || !"pre".equals(algorithm.getDefenseStage())) {
            throw new BadRequestException("指定的算法不存在、未启用或不是训练前防御算法");
        }
        if (!"vab://pre-defense/ldd".equals(algorithm.getImplementationPath())) {
            throw new BadRequestException("当前平台仅开放 LDD 训练前数据清洗算法");
        }

        if (resources.getHyperId() != null) {
            Hyper hyper = hyperService.findById(resources.getHyperId());
            if (hyper == null || !Boolean.TRUE.equals(hyper.getEnabled()) || !resources.getAlgorithmId().equals(hyper.getAlgorithmId())) {
                throw new BadRequestException("指定的超参数模板不存在、未启用或与算法不匹配");
            }
        }

        validateLddConfiguration(algorithm, resources.getHyperId(), resources.getCustomParams());
    }

    private void validateLddConfiguration(Algorithm algorithm, Long hyperId, String customParams) {
        Hyper hyper = hyperId == null ? null : hyperService.findById(hyperId);
        lddConfigurationPolicy.mergeAndValidate(
                algorithm.getDefaultConfig(),
                hyper == null ? null : hyper.getParameterConfig(),
                customParams);
    }

    private String buildCanceledResult(PreDefense task, LocalDateTime endTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "security-model-platform");
        result.put("stage", "pre_defense");
        result.put("status", "canceled");
        result.put("message", "任务已由用户取消");
        result.put("run_id", task.getCurrentRunId());
        result.put("attempt", task.getCurrentAttempt());
        result.put("error_code", "TASK_CANCELED");
        result.put("failure_reason", "用户取消任务");
        result.put("started_at", task.getStartTime() == null ? null : task.getStartTime().toString());
        result.put("finished_at", endTime.toString());
        result.put("artifacts", new LinkedHashMap<String, Object>());
        return JSON.toJSONString(result);
    }

    private DefenseTaskRequest buildExecutionRequest(PreDefense task) {
        Dataset dataset = datasetService.findById(task.getDatasetId());
        Algorithm algorithm = algorithmService.findById(task.getAlgorithmId());
        Hyper hyper = task.getHyperId() == null ? null : hyperService.findById(task.getHyperId());
        return DefenseTaskRequest.builder()
                .taskId(task.getTaskId())
                .attempt(nextAttempt(task.getExecutionSnapshot()))
                .runId(UUID.randomUUID().toString())
                .taskName(task.getTaskName())
                .stage("pre_defense")
                .description(task.getDescription())
                .createdBy(task.getCreateBy())
                .customParams(task.getCustomParams())
                .dataset(toDatasetRef(dataset))
                .algorithm(toAlgorithmRef(algorithm))
                .hyperParameter(toHyperRef(hyper))
                .build();
    }

    private String appendExecutionSnapshot(
            String existingSnapshot,
            DefenseTaskRequest request,
            LocalDateTime launchedAt,
            JSONObject quotaSnapshot) {
        JSONObject snapshot = new JSONObject();
        JSONArray attempts = new JSONArray();
        if (!ObjectUtil.isEmpty(existingSnapshot)) {
            try {
                JSONObject previous = JSON.parseObject(existingSnapshot);
                JSONArray previousAttempts = previous.getJSONArray("attempts");
                if (previousAttempts != null) {
                    attempts.addAll(previousAttempts);
                }
            } catch (Exception ignored) {
                log.warn("任务 {} 的历史执行快照无法解析，将从本次启动重新记录", request.getTaskId());
            }
        }
        JSONObject attempt = new JSONObject();
        attempt.put("attempt", request.getAttempt() == null ? attempts.size() + 1 : request.getAttempt());
        attempt.put("runId", request.getRunId());
        attempt.put("launchedAt", launchedAt.toString());
        attempt.put("request", JSON.parseObject(JSON.toJSONString(request)));
        attempt.put("runtime", executionFingerprintService.buildRuntimeSnapshot(request));
        attempt.put("quota", quotaSnapshot);
        attempt.put("snapshotHash", executionFingerprintService.calculateSha256(JSON.toJSONString(attempt)));
        attempts.add(attempt);
        snapshot.put("version", 2);
        snapshot.put("attempts", attempts);
        return snapshot.toJSONString();
    }

    private int nextAttempt(String existingSnapshot) {
        if (ObjectUtil.isEmpty(existingSnapshot)) {
            return 1;
        }
        try {
            JSONObject previous = JSON.parseObject(existingSnapshot);
            JSONArray attempts = previous.getJSONArray("attempts");
            return attempts == null ? 1 : attempts.size() + 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private DefenseResourceRef toDatasetRef(Dataset dataset) {
        return DefenseResourceRef.builder()
                .id(dataset.getDatasetId())
                .name(dataset.getDatasetName())
                .type(dataset.getDatasetType())
                .path(dataset.getFilePath())
                .build();
    }

    private DefenseResourceRef toAlgorithmRef(Algorithm algorithm) {
        return DefenseResourceRef.builder()
                .id(algorithm.getAlgorithmId())
                .name(algorithm.getAlgorithmName())
                .type(algorithm.getAlgorithmType())
                .category(algorithm.getAlgorithmCategory())
                .path(algorithm.getImplementationPath())
                .configJson(algorithm.getDefaultConfig())
                .schemaJson(algorithm.getConfigSchema())
                .build();
    }

    private DefenseResourceRef toHyperRef(Hyper hyper) {
        if (hyper == null) {
            return null;
        }
        return DefenseResourceRef.builder()
                .id(hyper.getTemplateId())
                .name(hyper.getTemplateName())
                .type("hyper_parameter")
                .configJson(hyper.getParameterConfig())
                .schemaJson(hyper.getParameterSchema())
                .build();
    }

    private Number asNumber(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
}
