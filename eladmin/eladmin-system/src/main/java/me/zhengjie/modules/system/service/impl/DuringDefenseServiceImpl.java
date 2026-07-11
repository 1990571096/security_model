package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.exception.EntityExistException;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.dto.DuringDefenseQueryCriteria;
import me.zhengjie.modules.system.mapper.DuringDefenseMapper;
import me.zhengjie.modules.system.service.DuringDefenseService;
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
 * 训练中防御任务服务实现
 * @author ZJING
 * @date 2025-08-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuringDefenseServiceImpl extends ServiceImpl<DuringDefenseMapper, DuringDefense> implements DuringDefenseService {

    private final DuringDefenseMapper duringDefenseMapper;
    private final MockDefenseTaskExecutor mockDefenseTaskExecutor;
    // TODO: 需要注入DatasetService、AlgorithmService、ModelService和HyperService，用于验证关联数据
    // private final DatasetService datasetService;
    // private final AlgorithmService algorithmService;
    // private final ModelService modelService;
    // private final HyperService hyperService;

    @Override
    public DuringDefense findById(Long taskId) {
        DuringDefense task = duringDefenseMapper.selectById(taskId);
        if (ObjectUtil.isNull(task)) {
            throw new BadRequestException("任务不存在");
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(DuringDefense resources) {
        // 检查任务名称是否已存在
        if (duringDefenseMapper.findByTaskName(resources.getTaskName()) != null) {
            throw new EntityExistException(DuringDefense.class, "taskName", resources.getTaskName());
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
        
        duringDefenseMapper.insert(resources);
        log.info("用户 {} 创建了新的训练中防御任务: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTaskName(),
                resources.getTaskId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DuringDefense resources) {
        DuringDefense task = findById(resources.getTaskId());

        // 检查任务名称是否已被其他任务使用
        DuringDefense taskByName = duringDefenseMapper.findByTaskName(resources.getTaskName());
        if (taskByName != null && !taskByName.getTaskId().equals(resources.getTaskId())) {
            throw new EntityExistException(DuringDefense.class, "taskName", resources.getTaskName());
        }

        // 验证任务参数
        validateTask(resources);

        duringDefenseMapper.updateById(resources);
        log.info("用户 {} 更新了训练中防御任务: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTaskName(),
                resources.getTaskId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Set<Long> taskIds) {
        String currentUser = SecurityUtils.getCurrentUsername();
        
        for (Long taskId : taskIds) {
            DuringDefense task = findById(taskId);
            
            // 权限检查：只能删除自己创建的任务
            if (!ObjectUtil.equal(task.getCreateBy(), currentUser)) {
                throw new BadRequestException("只能删除自己创建的任务【" + task.getTaskName() + "】");
            }
            
            // 业务检查：运行中的任务不能删除
            if ("running".equals(task.getStatus())) {
                throw new BadRequestException("任务【" + task.getTaskName() + "】正在运行中，无法删除");
            }

            // 业务检查：检查任务是否可以删除
            if (!canDeleteTask(taskId)) {
                throw new BadRequestException("任务【" + task.getTaskName() + "】不能删除，可能存在依赖关系");
            }
        }

        duringDefenseMapper.deleteBatchIds(taskIds);
        log.info("用户 {} 删除了 {} 个训练中防御任务", currentUser, taskIds.size());
    }

    @Override
    public DuringDefense findByTaskName(String taskName) {
        return duringDefenseMapper.findByTaskName(taskName);
    }

    @Override
    public PageResult<DuringDefense> queryAll(DuringDefenseQueryCriteria criteria, Page<Object> page) {
        criteria.setOffset(page.offset());
        IPage<DuringDefense> taskPage = duringDefenseMapper.findAll(criteria, page);
        return PageUtil.toPage(taskPage);
    }

    @Override
    public List<DuringDefense> queryAll(DuringDefenseQueryCriteria criteria) {
        return duringDefenseMapper.findAll(criteria);
    }

    @Override
    public void download(List<DuringDefense> tasks, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DuringDefense task : tasks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("任务ID", task.getTaskId());
            map.put("任务名称", task.getTaskName());
            map.put("任务描述", task.getDescription());
            map.put("任务状态", task.getStatus());
            map.put("数据集ID", task.getDatasetId());
            map.put("输入模型ID", task.getInputModelId());
            map.put("算法ID", task.getAlgorithmId());
            map.put("超参数模板ID", task.getHyperId());
            map.put("进度", task.getProgress() + "%");
            map.put("输出模型ID", task.getOutputModelId());
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
        DuringDefense task = findById(taskId);
        
        // 检查任务状态
        if (!"pending".equals(task.getStatus())) {
            throw new BadRequestException("只有等待中的任务才能启动");
        }

        // 检查任务是否可以启动
        if (!canStartTask(taskId)) {
            throw new BadRequestException("任务不满足启动条件，请检查相关资源是否可用");
        }
        
        // 更新任务状态为运行中
        task.setStatus("running");
        task.setProgress(1);
        task.setStartTime(LocalDateTime.now());
        duringDefenseMapper.updateById(task);
        
        mockDefenseTaskExecutor.runDuringDefense(taskId);

        log.info("启动训练中防御任务: {} (ID: {})", task.getTaskName(), taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTaskStatus(Long taskId, String status, Integer progress) {
        duringDefenseMapper.updateStatus(taskId, status, progress);
        
        // 如果任务完成，设置结束时间
        if ("success".equals(status) || "failed".equals(status)) {
            DuringDefense task = findById(taskId);
            task.setEndTime(LocalDateTime.now());
            duringDefenseMapper.updateById(task);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTrainingResult(Long taskId, Long outputModelId, String trainingLog) {
        duringDefenseMapper.updateTrainingResult(taskId, outputModelId, trainingLog);
        log.info("更新任务 {} 的训练结果，输出模型ID: {}", taskId, outputModelId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTrainingLog(Long taskId, String trainingLog) {
        duringDefenseMapper.updateTrainingLog(taskId, trainingLog);
        log.info("更新任务 {} 的训练日志", taskId);
    }

    @Override
    public Integer getTaskProgress(Long taskId) {
        DuringDefense task = findById(taskId);
        return task.getProgress();
    }

    @Override
    public List<DuringDefense> findByStatus(String status) {
        return duringDefenseMapper.findByStatus(status);
    }

    @Override
    public List<DuringDefense> findByDatasetId(Long datasetId) {
        return duringDefenseMapper.findByDatasetId(datasetId);
    }

    @Override
    public List<DuringDefense> findByInputModelId(Long inputModelId) {
        return duringDefenseMapper.findByInputModelId(inputModelId);
    }

    @Override
    public List<DuringDefense> findByAlgorithmId(Long algorithmId) {
        return duringDefenseMapper.findByAlgorithmId(algorithmId);
    }

    @Override
    public List<DuringDefense> findByCreateBy(String createBy) {
        return duringDefenseMapper.findByCreateBy(createBy);
    }

    @Override
    public DuringDefense findByOutputModelId(Long outputModelId) {
        return duringDefenseMapper.findByOutputModelId(outputModelId);
    }

    @Override
    public void validateTask(DuringDefense resources) {
        // 基础参数验证
        if (ObjectUtil.isEmpty(resources.getTaskName())) {
            throw new BadRequestException("任务名称不能为空");
        }
        if (ObjectUtil.isNull(resources.getDatasetId())) {
            throw new BadRequestException("数据集ID不能为空");
        }
        if (ObjectUtil.isNull(resources.getInputModelId())) {
            throw new BadRequestException("输入模型ID不能为空");
        }
        if (ObjectUtil.isNull(resources.getAlgorithmId())) {
            throw new BadRequestException("算法ID不能为空");
        }

        // TODO: 验证数据集是否存在且可用
        // Dataset dataset = datasetService.findById(resources.getDatasetId());
        // if (dataset == null || !dataset.getEnabled() || !"success".equals(dataset.getParseStatus())) {
        //     throw new BadRequestException("指定的数据集不存在或不可用");
        // }

        // TODO: 验证模型是否存在且可用
        // Model inputModel = modelService.findById(resources.getInputModelId());
        // if (inputModel == null || !"available".equals(inputModel.getStatus())) {
        //     throw new BadRequestException("指定的输入模型不存在或不可用");
        // }
        
        // TODO: 验证算法是否存在且为训练中防御算法
        // Algorithm algorithm = algorithmService.findById(resources.getAlgorithmId());
        // if (algorithm == null || !"during".equals(algorithm.getDefenseStage())) {
        //     throw new BadRequestException("指定的算法不存在或不是训练中防御算法");
        // }

        // TODO: 验证超参数模板（如果指定）
        // if (resources.getHyperId() != null) {
        //     Hyper hyper = hyperService.findById(resources.getHyperId());
        //     if (hyper == null || !hyper.getEnabled() || !hyper.getAlgorithmId().equals(resources.getAlgorithmId())) {
        //         throw new BadRequestException("指定的超参数模板不存在或与算法不匹配");
        //     }
        // }
    }

    @Override
    public Map<String, Object> getTaskStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // 获取基础统计
        List<Map<String, Object>> taskStats = duringDefenseMapper.getTaskStatistics();
        Map<String, Long> statusMap = new HashMap<>();
        for (Map<String, Object> stat : taskStats) {
            statusMap.put((String) stat.get("taskStatus"), ((Number) stat.get("count")).longValue());
        }
        stats.put("statusStats", statusMap);
        
        // 获取算法类型统计
        List<Map<String, Object>> algorithmStats = duringDefenseMapper.getAlgorithmTypeStatistics();
        stats.put("algorithmStats", algorithmStats);
        
        // 总数统计
        DuringDefenseQueryCriteria criteria = new DuringDefenseQueryCriteria();
        List<DuringDefense> allTasks = duringDefenseMapper.findAll(criteria);
        stats.put("totalCount", allTasks.size());
        
        return stats;
    }

    @Override
    public Map<String, Object> getAlgorithmTypeStatistics() {
        Map<String, Object> stats = new HashMap<>();
        List<Map<String, Object>> algorithmStats = duringDefenseMapper.getAlgorithmTypeStatistics();
        stats.put("algorithmTypeStats", algorithmStats);
        return stats;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startBatchTasks(Set<Long> taskIds) {
        Map<String, Object> result = new HashMap<>();
        List<Long> successIds = new ArrayList<>();
        List<String> failureReasons = new ArrayList<>();
        
        for (Long taskId : taskIds) {
            try {
                startTask(taskId);
                successIds.add(taskId);
            } catch (Exception e) {
                log.warn("启动任务{}失败: {}", taskId, e.getMessage());
                failureReasons.add("任务" + taskId + ": " + e.getMessage());
            }
        }
        
        result.put("successCount", successIds.size());
        result.put("successIds", successIds);
        result.put("failureCount", failureReasons.size());
        result.put("failureReasons", failureReasons);
        
        return result;
    }

    @Override
    public boolean canStartTask(Long taskId) {
        try {
            DuringDefense task = findById(taskId);
            
            // 检查任务状态
            if (!"pending".equals(task.getStatus())) {
                return false;
            }
            
            // TODO: 检查数据集是否可用
            // Dataset dataset = datasetService.findById(task.getDatasetId());
            // if (dataset == null || !dataset.getEnabled() || !"success".equals(dataset.getParseStatus())) {
            //     return false;
            // }
            
            // TODO: 检查输入模型是否可用
            // Model inputModel = modelService.findById(task.getInputModelId());
            // if (inputModel == null || !"available".equals(inputModel.getStatus())) {
            //     return false;
            // }
            
            // TODO: 检查算法是否可用
            // Algorithm algorithm = algorithmService.findById(task.getAlgorithmId());
            // if (algorithm == null || !algorithm.getEnabled()) {
            //     return false;
            // }
            
            return true;
        } catch (Exception e) {
            log.warn("检查任务{}是否可启动时发生异常: {}", taskId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean canDeleteTask(Long taskId) {
        try {
            DuringDefense task = findById(taskId);
            
            // 运行中的任务不能删除
            if ("running".equals(task.getStatus())) {
                return false;
            }
            
            // TODO: 检查是否有其他依赖关系
            // 例如：输出模型是否被其他任务使用
            
            return true;
        } catch (Exception e) {
            log.warn("检查任务{}是否可删除时发生异常: {}", taskId, e.getMessage());
            return false;
        }
    }
}
