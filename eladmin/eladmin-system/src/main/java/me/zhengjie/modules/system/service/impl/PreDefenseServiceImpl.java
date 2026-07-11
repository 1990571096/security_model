package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
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
import me.zhengjie.modules.system.service.executor.DefenseTaskExecutor;
import me.zhengjie.modules.system.service.executor.dto.DefenseResourceRef;
import me.zhengjie.modules.system.service.executor.dto.DefenseTaskRequest;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
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

        // 检查任务名称是否已被其他任务使用
        PreDefense taskByName = preDefenseMapper.findByTaskName(resources.getTaskName());
        if (taskByName != null && !taskByName.getTaskId().equals(resources.getTaskId())) {
            throw new EntityExistException(PreDefense.class, "taskName", resources.getTaskName());
        }

        // 验证任务参数
        validateTask(resources);

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
                stats.put("running_tasks", 0L);
                stats.put("pending_tasks", 0L);
                stats.put("avg_progress", 0.0);
                stats.put("avg_duration_minutes", 0.0);
            }

            // 计算成功率
            Long totalTasks = (Long) stats.get("total_tasks");
            Long successTasks = (Long) stats.get("success_tasks");
            if (totalTasks != null && totalTasks > 0 && successTasks != null) {
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
        
        // 检查任务状态
        if (!"pending".equals(task.getStatus())) {
            throw new BadRequestException("只有等待中的任务才能启动");
        }

        validateTask(task);
        
        // 更新任务状态为运行中
        task.setStatus("running");
        task.setProgress(1);
        task.setStartTime(LocalDateTime.now());
        preDefenseMapper.updateById(task);
        
        defenseTaskExecutor.runPreDefense(buildExecutionRequest(task));

        log.info("启动训练前防御任务: {} (ID: {})", task.getTaskName(), taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTaskStatus(Long taskId, String status, Integer progress) {
        preDefenseMapper.updateStatus(taskId, status, progress);
        
        // 如果任务完成，设置结束时间
        if ("success".equals(status) || "failed".equals(status)) {
            PreDefense task = findById(taskId);
            task.setEndTime(LocalDateTime.now());
            preDefenseMapper.updateById(task);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTaskResult(Long taskId, Long outputDatasetId, String cleaningResult) {
        preDefenseMapper.updateResult(taskId, outputDatasetId, cleaningResult);
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

        if (resources.getHyperId() != null) {
            Hyper hyper = hyperService.findById(resources.getHyperId());
            if (hyper == null || !Boolean.TRUE.equals(hyper.getEnabled()) || !resources.getAlgorithmId().equals(hyper.getAlgorithmId())) {
                throw new BadRequestException("指定的超参数模板不存在、未启用或与算法不匹配");
            }
        }
    }

    private DefenseTaskRequest buildExecutionRequest(PreDefense task) {
        Dataset dataset = datasetService.findById(task.getDatasetId());
        Algorithm algorithm = algorithmService.findById(task.getAlgorithmId());
        Hyper hyper = task.getHyperId() == null ? null : hyperService.findById(task.getHyperId());
        return DefenseTaskRequest.builder()
                .taskId(task.getTaskId())
                .taskName(task.getTaskName())
                .stage("pre_defense")
                .description(task.getDescription())
                .createdBy(task.getCreateBy())
                .dataset(toDatasetRef(dataset))
                .algorithm(toAlgorithmRef(algorithm))
                .hyperParameter(toHyperRef(hyper))
                .build();
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
}
