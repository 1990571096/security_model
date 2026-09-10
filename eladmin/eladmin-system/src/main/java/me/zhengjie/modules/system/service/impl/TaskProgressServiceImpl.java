package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.Model;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.domain.dto.PreDefenseQueryCriteria;
import me.zhengjie.modules.system.domain.dto.TaskProgressDto;
import me.zhengjie.modules.system.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import me.zhengjie.modules.system.service.policy.PreDefenseTaskStatePolicy;
import me.zhengjie.modules.system.service.policy.TaskExecutionQuotaPolicy;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 任务进度查询服务实现
 * @author ZJING
 * @date 2025-08-09
 */
@Slf4j
@Service
public class TaskProgressServiceImpl implements TaskProgressService {

    private static final int MAX_BATCH_TASKS = 100;
    private static final String PRE_DEFENSE_TASK_TYPE = "pre_defense";

    private final PreDefenseService preDefenseService;
    private final AlgorithmService algorithmService;
    private final DatasetService datasetService;
    private final ModelService modelService;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final TaskExecutionQuotaPolicy taskExecutionQuotaPolicy;

    public TaskProgressServiceImpl(
            PreDefenseService preDefenseService,
            AlgorithmService algorithmService,
            DatasetService datasetService,
            ModelService modelService,
            @Qualifier("taskAsync") ThreadPoolTaskExecutor taskExecutor,
            TaskExecutionQuotaPolicy taskExecutionQuotaPolicy) {
        this.preDefenseService = preDefenseService;
        this.algorithmService = algorithmService;
        this.datasetService = datasetService;
        this.modelService = modelService;
        this.taskExecutor = taskExecutor;
        this.taskExecutionQuotaPolicy = taskExecutionQuotaPolicy;
    }

    @Override
    public List<TaskProgressDto> getRunningTasks() {
        List<TaskProgressDto> runningTasks = new ArrayList<>();

        try {
            // 获取运行中的训练前防御任务
            List<PreDefense> runningPreDefense = preDefenseService.findByStatus("running");
            runningTasks.addAll(runningPreDefense.stream()
                    .map(this::convertPreDefenseToDto)
                    .collect(Collectors.toList()));

            // 按开始时间排序，最新的在前
            runningTasks.sort((a, b) -> {
                if (a.getStartTime() == null && b.getStartTime() == null) return 0;
                if (a.getStartTime() == null) return 1;
                if (b.getStartTime() == null) return -1;
                return b.getStartTime().compareTo(a.getStartTime());
            });

        } catch (Exception e) {
            log.error("获取运行中任务失败: {}", e.getMessage());
            throw new BadRequestException("获取运行中任务失败");
        }

        return runningTasks;
    }

    @Override
    public List<TaskProgressDto> getAllTasks(String status, String taskType, String blurry) {
        ensurePreDefenseTaskType(taskType);
        if (status != null && !status.trim().isEmpty()
                && !PreDefenseTaskStatePolicy.isSupportedStatus(status)) {
            throw new BadRequestException("不支持的任务状态: " + status);
        }
        List<TaskProgressDto> allTasks = new ArrayList<>();

        try {
            PreDefenseQueryCriteria criteria = new PreDefenseQueryCriteria();
            criteria.setStatus(status);
            criteria.setBlurry(blurry);
            List<PreDefense> preTasks = preDefenseService.queryAll(criteria);
            allTasks.addAll(preTasks.stream()
                    .map(this::convertPreDefenseToDto)
                    .collect(Collectors.toList()));

            // 按创建时间排序，最新的在前
            allTasks.sort((a, b) -> {
                if (a.getCreateTime() == null && b.getCreateTime() == null) return 0;
                if (a.getCreateTime() == null) return 1;
                if (b.getCreateTime() == null) return -1;
                return b.getCreateTime().compareTo(a.getCreateTime());
            });

        } catch (Exception e) {
            log.error("获取任务列表失败: {}", e.getMessage());
            throw new BadRequestException("获取任务列表失败");
        }

        return allTasks;
    }

    @Override
    public TaskProgressDto getTaskDetail(String taskType, Long taskId) {
        ensurePreDefenseTaskType(taskType);
        try {
            PreDefense preDefense = preDefenseService.findById(taskId);
            return convertPreDefenseToDto(preDefense);
        } catch (Exception e) {
            log.error("获取任务详情失败: taskType={}, taskId={}, error={}", taskType, taskId, e.getMessage());
            throw new BadRequestException("获取任务详情失败");
        }
    }

    @Override
    public Map<String, Object> getTaskStatistics() {
        Map<String, Object> statistics = new HashMap<>();

        try {
            // 按任务类型统计
            Map<String, Integer> typeStats = new HashMap<>();
            typeStats.put("pre_defense", preDefenseService.queryAll(new PreDefenseQueryCriteria()).size());

            // 按状态统计
            Map<String, Integer> statusStats = new HashMap<>();
            Arrays.asList("pending", "running", "success", "failed", "canceled").forEach(
                    status -> statusStats.put(status, preDefenseService.findByStatus(status).size()));

            // 运行中任务统计
            int runningCount = statusStats.get("running");

            statistics.put("typeStats", typeStats);
            statistics.put("statusStats", statusStats);
            statistics.put("runningCount", runningCount);
            statistics.put("totalCount", typeStats.values().stream().mapToInt(Integer::intValue).sum());

        } catch (Exception e) {
            log.error("获取任务统计失败: {}", e.getMessage());
            throw new BadRequestException("获取任务统计失败");
        }

        return statistics;
    }

    @Override
    public Map<String, Object> getExecutorStatus() {
        ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("poolSize", executor.getPoolSize());
        status.put("activeCount", executor.getActiveCount());
        status.put("corePoolSize", executor.getCorePoolSize());
        status.put("maxPoolSize", executor.getMaximumPoolSize());
        status.put("queueSize", executor.getQueue().size());
        status.put("queueRemainingCapacity", executor.getQueue().remainingCapacity());
        status.put("queueCapacity", executor.getQueue().size() + executor.getQueue().remainingCapacity());
        status.put("completedTaskCount", executor.getCompletedTaskCount());
        status.put("taskCount", executor.getTaskCount());
        status.put("saturated", executor.getActiveCount() >= executor.getMaximumPoolSize()
                && executor.getQueue().remainingCapacity() == 0);
        status.put("acceptingTasks", !executor.isShutdown() && !executor.isTerminated());
        status.put("quota", taskExecutionQuotaPolicy.getStatus());
        return status;
    }

    @Override
    public Map<String, Object> getBatchTaskProgress(List<Map<String, Object>> taskInfos) {
        validateBatchTaskInfos(taskInfos);
        Map<String, Object> progressMap = new HashMap<>();

        for (Map<String, Object> taskInfo : taskInfos) {
            String taskType = (String) taskInfo.get("taskType");
            Long taskId = parseTaskId(taskInfo.get("taskId"));
            String key = taskType + "_" + taskId;
            try {
                Integer progress = preDefenseService.getTaskProgress(taskId);
                progressMap.put(key, progress != null ? progress : -1);

            } catch (Exception e) {
                log.warn("获取任务进度失败: taskType={}, taskId={}, reason={}",
                        taskType, taskId, e.getMessage());
                progressMap.put(key, -1);
            }
        }

        return progressMap;
    }

    private void validateBatchTaskInfos(List<Map<String, Object>> taskInfos) {
        if (taskInfos == null || taskInfos.isEmpty()) {
            throw new BadRequestException("任务信息列表不能为空");
        }
        if (taskInfos.size() > MAX_BATCH_TASKS) {
            throw new BadRequestException("单次最多处理 " + MAX_BATCH_TASKS + " 个任务");
        }
        for (Map<String, Object> taskInfo : taskInfos) {
            if (taskInfo == null) {
                throw new BadRequestException("任务信息不能包含空值");
            }
            if (!PRE_DEFENSE_TASK_TYPE.equals(taskInfo.get("taskType"))) {
                throw new BadRequestException("当前平台仅支持 LDD 训练前清洗任务");
            }
            parseTaskId(taskInfo.get("taskId"));
        }
    }

    private Long parseTaskId(Object rawTaskId) {
        if (rawTaskId == null) {
            throw new BadRequestException("任务ID不能为空");
        }
        try {
            long taskId = Long.parseLong(rawTaskId.toString());
            if (taskId <= 0) {
                throw new NumberFormatException("non-positive task ID");
            }
            return taskId;
        } catch (NumberFormatException e) {
            throw new BadRequestException("任务ID必须是正整数");
        }
    }

    @Override
    public void refreshTaskStatus() {
        // 这里可以实现一些缓存刷新逻辑
        log.info("任务状态刷新完成");
    }

    @Override
    public TaskProgressDto convertPreDefenseToDto(PreDefense preDefense) {
        TaskProgressDto dto = new TaskProgressDto();

        // 基础信息
        dto.setTaskId(preDefense.getTaskId());
        dto.setTaskName(preDefense.getTaskName());
        dto.setDescription(preDefense.getDescription());
        dto.setTaskType("pre_defense");
        dto.setStatus(preDefense.getStatus());
        dto.setProgress(preDefense.getProgress());
        dto.setStartTime(preDefense.getStartTime());
        dto.setEndTime(preDefense.getEndTime());
        dto.setCreateTime(preDefense.getCreateTime());
        dto.setCreateBy(preDefense.getCreateBy());

        // 训练前防御特有字段
        dto.setDatasetId(preDefense.getDatasetId());
        dto.setOutputDatasetId(preDefense.getOutputDatasetId());
        dto.setCleaningResult(preDefense.getCleaningResult());

        // 公共字段
        dto.setAlgorithmId(preDefense.getAlgorithmId());
        dto.setHyperId(preDefense.getHyperId());
        dto.setCustomParams(preDefense.getCustomParams());

        // 设置关联信息的名称（用于显示）
        setRelatedNames(dto);

        return dto;
    }

    /**
     * 设置关联信息的名称（用于前端显示）
     */
    private void setRelatedNames(TaskProgressDto dto) {
        try {
            // 设置算法名称
            if (dto.getAlgorithmId() != null) {
                Algorithm algorithm = algorithmService.findById(dto.getAlgorithmId());
                dto.setAlgorithmName(algorithm.getAlgorithmName());
            }

            // 设置数据集名称
            if (dto.getDatasetId() != null) {
                Dataset dataset = datasetService.findById(dto.getDatasetId());
                dto.setDatasetName(dataset.getDatasetName());
            }

            // 设置模型名称（优先显示输入模型）
            if (dto.getInputModelId() != null) {
                Model model = modelService.findById(dto.getInputModelId());
                dto.setModelName(model.getModelName());
            } else if (dto.getOutputModelId() != null) {
                Model model = modelService.findById(dto.getOutputModelId());
                dto.setModelName(model.getModelName());
            }

        } catch (Exception e) {
            log.warn("设置关联信息名称失败: {}", e.getMessage());
        }
    }

    private void ensurePreDefenseTaskType(String taskType) {
        if (!ObjectUtil.isEmpty(taskType) && !"pre_defense".equals(taskType)) {
            throw new BadRequestException("当前任务中心仅支持 LDD 训练前清洗任务");
        }
    }
}
