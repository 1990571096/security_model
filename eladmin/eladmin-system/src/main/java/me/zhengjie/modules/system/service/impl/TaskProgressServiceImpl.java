package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.Model;
import me.zhengjie.modules.system.domain.PostDefense;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.domain.dto.DuringDefenseQueryCriteria;
import me.zhengjie.modules.system.domain.dto.PostDefenseQueryCriteria;
import me.zhengjie.modules.system.domain.dto.PreDefenseQueryCriteria;
import me.zhengjie.modules.system.domain.dto.TaskProgressDto;
import me.zhengjie.modules.system.service.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务进度查询服务实现
 * @author ZJING
 * @date 2025-08-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskProgressServiceImpl implements TaskProgressService {

    private final PreDefenseService preDefenseService;
    private final DuringDefenseService duringDefenseService;
    private final PostDefenseService postDefenseService;
    private final AlgorithmService algorithmService;
    private final DatasetService datasetService;
    private final ModelService modelService;

    @Override
    public List<TaskProgressDto> getRunningTasks() {
        List<TaskProgressDto> runningTasks = new ArrayList<>();

        try {
            // 获取运行中的训练前防御任务
            List<PreDefense> runningPreDefense = preDefenseService.findByStatus("running");
            runningTasks.addAll(runningPreDefense.stream()
                    .map(this::convertPreDefenseToDto)
                    .collect(Collectors.toList()));

            // 获取运行中的训练中防御任务
            List<DuringDefense> runningDuringDefense = duringDefenseService.findByStatus("running");
            runningTasks.addAll(runningDuringDefense.stream()
                    .map(this::convertDuringDefenseToDto)
                    .collect(Collectors.toList()));

            // 获取运行中的训练后防御任务
            List<PostDefense> runningPostDefense = postDefenseService.findByStatus("running");
            runningTasks.addAll(runningPostDefense.stream()
                    .map(this::convertPostDefenseToDto)
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
    public List<TaskProgressDto> getAllTasks(String status, String taskType) {
        List<TaskProgressDto> allTasks = new ArrayList<>();

        try {
            // 根据任务类型筛选
            if (ObjectUtil.isEmpty(taskType) || "pre_defense".equals(taskType)) {
                List<PreDefense> preTasks = ObjectUtil.isEmpty(status) ?
                        preDefenseService.queryAll(new PreDefenseQueryCriteria()) :
                        preDefenseService.findByStatus(status);
                allTasks.addAll(preTasks.stream()
                        .map(this::convertPreDefenseToDto)
                        .collect(Collectors.toList()));
            }

            if (ObjectUtil.isEmpty(taskType) || "during_defense".equals(taskType)) {
                List<DuringDefense> duringTasks = ObjectUtil.isEmpty(status) ?
                        duringDefenseService.queryAll(new DuringDefenseQueryCriteria()) :
                        duringDefenseService.findByStatus(status);
                allTasks.addAll(duringTasks.stream()
                        .map(this::convertDuringDefenseToDto)
                        .collect(Collectors.toList()));
            }

            if (ObjectUtil.isEmpty(taskType) || "post_defense".equals(taskType)) {
                List<PostDefense> postTasks = ObjectUtil.isEmpty(status) ?
                        postDefenseService.queryAll(new PostDefenseQueryCriteria()) :
                        postDefenseService.findByStatus(status);
                allTasks.addAll(postTasks.stream()
                        .map(this::convertPostDefenseToDto)
                        .collect(Collectors.toList()));
            }

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
        try {
            switch (taskType) {
                case "pre_defense":
                    PreDefense preDefense = preDefenseService.findById(taskId);
                    return convertPreDefenseToDto(preDefense);
                case "during_defense":
                    DuringDefense duringDefense = duringDefenseService.findById(taskId);
                    return convertDuringDefenseToDto(duringDefense);
                case "post_defense":
                    PostDefense postDefense = postDefenseService.findById(taskId);
                    return convertPostDefenseToDto(postDefense);
                default:
                    throw new BadRequestException("不支持的任务类型: " + taskType);
            }
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
            typeStats.put("during_defense", duringDefenseService.queryAll(new DuringDefenseQueryCriteria()).size());
            typeStats.put("post_defense", postDefenseService.queryAll(new PostDefenseQueryCriteria()).size());

            // 按状态统计
            Map<String, Integer> statusStats = new HashMap<>();
            Arrays.asList("pending", "running", "success", "failed").forEach(status -> {
                int count = 0;
                count += preDefenseService.findByStatus(status).size();
                count += duringDefenseService.findByStatus(status).size();
                count += postDefenseService.findByStatus(status).size();
                statusStats.put(status, count);
            });

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
    public Map<String, Object> getBatchTaskProgress(List<Map<String, Object>> taskInfos) {
        Map<String, Object> progressMap = new HashMap<>();

        for (Map<String, Object> taskInfo : taskInfos) {
            try {
                String taskType = (String) taskInfo.get("taskType");
                Long taskId = Long.valueOf(taskInfo.get("taskId").toString());
                String key = taskType + "_" + taskId;

                Integer progress = null;
                switch (taskType) {
                    case "pre_defense":
                        progress = preDefenseService.getTaskProgress(taskId);
                        break;
                    case "during_defense":
                        progress = duringDefenseService.getTaskProgress(taskId);
                        break;
                    case "post_defense":
                        progress = postDefenseService.getTaskProgress(taskId);
                        break;
                }
                progressMap.put(key, progress != null ? progress : -1);

            } catch (Exception e) {
                log.warn("获取任务进度失败: {}", e.getMessage());
                String key = taskInfo.get("taskType") + "_" + taskInfo.get("taskId");
                progressMap.put(key, -1);
            }
        }

        return progressMap;
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

    @Override
    public TaskProgressDto convertDuringDefenseToDto(DuringDefense duringDefense) {
        TaskProgressDto dto = new TaskProgressDto();

        // 基础信息
        dto.setTaskId(duringDefense.getTaskId());
        dto.setTaskName(duringDefense.getTaskName());
        dto.setDescription(duringDefense.getDescription());
        dto.setTaskType("during_defense");
        dto.setStatus(duringDefense.getStatus());
        dto.setProgress(duringDefense.getProgress());
        dto.setStartTime(duringDefense.getStartTime());
        dto.setEndTime(duringDefense.getEndTime());
        dto.setCreateTime(duringDefense.getCreateTime());
        dto.setCreateBy(duringDefense.getCreateBy());

        // 训练中防御特有字段
        dto.setDatasetId(duringDefense.getDatasetId());
        dto.setInputModelId(duringDefense.getInputModelId());
        dto.setOutputModelId(duringDefense.getOutputModelId());

        // 公共字段
        dto.setAlgorithmId(duringDefense.getAlgorithmId());
        dto.setHyperId(duringDefense.getHyperId());
        dto.setCustomParams(duringDefense.getCustomParams());

        // 设置关联信息的名称（用于显示）
        setRelatedNames(dto);

        return dto;
    }

    @Override
    public TaskProgressDto convertPostDefenseToDto(PostDefense postDefense) {
        TaskProgressDto dto = new TaskProgressDto();

        // 基础信息
        dto.setTaskId(postDefense.getTaskId());
        dto.setTaskName(postDefense.getTaskName());
        dto.setDescription(postDefense.getDescription());
        dto.setTaskType("post_defense");
        dto.setStatus(postDefense.getStatus());
        dto.setProgress(postDefense.getProgress());
        dto.setStartTime(postDefense.getStartTime());
        dto.setEndTime(postDefense.getEndTime());
        dto.setCreateTime(postDefense.getCreateTime());
        dto.setCreateBy(postDefense.getCreateBy());

        // 训练后防御特有字段
        dto.setInputModelId(postDefense.getInputModelId());
        dto.setTestDatasetId(postDefense.getTestDatasetId());
        dto.setOutputModelId(postDefense.getOutputModelId());
        dto.setDefenseResult(postDefense.getDefenseResult());

        // 公共字段
        dto.setAlgorithmId(postDefense.getAlgorithmId());
        dto.setHyperId(postDefense.getHyperId());
        dto.setCustomParams(postDefense.getCustomParams());

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
}