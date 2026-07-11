package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.exception.EntityExistException;
import me.zhengjie.modules.system.domain.PostDefense;
import me.zhengjie.modules.system.domain.dto.PostDefenseQueryCriteria;
import me.zhengjie.modules.system.mapper.PostDefenseMapper;
import me.zhengjie.modules.system.service.PostDefenseService;
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
 * 训练后防御任务服务实现
 * @author ZJING
 * @date 2025-08-08
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostDefenseServiceImpl extends ServiceImpl<PostDefenseMapper, PostDefense> implements PostDefenseService {

    private final PostDefenseMapper postDefenseMapper;

    @Override
    public PostDefense findById(Long taskId) {
        PostDefense task = postDefenseMapper.selectById(taskId);
        if (ObjectUtil.isNull(task)) {
            throw new BadRequestException("任务不存在");
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(PostDefense resources) {
        // 检查任务名称是否已存在
        if (postDefenseMapper.findByTaskName(resources.getTaskName()) != null) {
            throw new EntityExistException(PostDefense.class, "taskName", resources.getTaskName());
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

        postDefenseMapper.insert(resources);
        log.info("用户 {} 创建了新的训练后防御任务: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTaskName(),
                resources.getTaskId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(PostDefense resources) {
        PostDefense task = findById(resources.getTaskId());

        // 检查任务名称是否已被其他任务使用
        PostDefense taskByName = postDefenseMapper.findByTaskName(resources.getTaskName());
        if (taskByName != null && !taskByName.getTaskId().equals(resources.getTaskId())) {
            throw new EntityExistException(PostDefense.class, "taskName", resources.getTaskName());
        }

        // 验证任务参数
        validateTask(resources);

        postDefenseMapper.updateById(resources);
        log.info("用户 {} 更新了训练后防御任务: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getTaskName(),
                resources.getTaskId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Set<Long> taskIds) {
        String currentUser = SecurityUtils.getCurrentUsername();

        for (Long taskId : taskIds) {
            PostDefense task = findById(taskId);

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

        postDefenseMapper.deleteBatchIds(taskIds);
        log.info("用户 {} 删除了 {} 个训练后防御任务", currentUser, taskIds.size());
    }

    @Override
    public PostDefense findByTaskName(String taskName) {
        return postDefenseMapper.findByTaskName(taskName);
    }

    @Override
    public PageResult<PostDefense> queryAll(PostDefenseQueryCriteria criteria, Page<Object> page) {
        criteria.setOffset(page.offset());
        IPage<PostDefense> taskPage = postDefenseMapper.findAll(criteria, page);
        return PageUtil.toPage(taskPage);
    }

    @Override
    public List<PostDefense> queryAll(PostDefenseQueryCriteria criteria) {
        return postDefenseMapper.findAll(criteria);
    }

    @Override
    public void download(List<PostDefense> tasks, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PostDefense task : tasks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("任务ID", task.getTaskId());
            map.put("任务名称", task.getTaskName());
            map.put("任务描述", task.getDescription());
            map.put("任务状态", task.getStatus());
            map.put("输入模型ID", task.getInputModelId());
            map.put("测试数据集ID", task.getTestDatasetId());
            map.put("算法ID", task.getAlgorithmId());
            map.put("超参数模板ID", task.getHyperId());
            map.put("进度", task.getProgress() + "%");
            map.put("防御结果", task.getDefenseResult());
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
        PostDefense task = findById(taskId);

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
        postDefenseMapper.updateById(task);

        // TODO: 这里应该调用实际的防御算法执行逻辑
        // 可能需要异步执行，或者调用外部防御服务

        log.info("启动训练后防御任务: {} (ID: {})", task.getTaskName(), taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTaskStatus(Long taskId, String status, Integer progress) {
        postDefenseMapper.updateStatus(taskId, status, progress);

        // 如果任务完成，设置结束时间
        if ("success".equals(status) || "failed".equals(status)) {
            PostDefense task = findById(taskId);
            task.setEndTime(LocalDateTime.now());
            postDefenseMapper.updateById(task);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDefenseResult(Long taskId, String defenseResult, Long outputModelId, String outputModelPath) {
        postDefenseMapper.updateDefenseResult(taskId, defenseResult, outputModelId, outputModelPath);
        log.info("更新任务 {} 的防御结果: {}", taskId, defenseResult);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePerformanceMetrics(Long taskId, String performanceMetrics) {
        postDefenseMapper.updatePerformanceMetrics(taskId, performanceMetrics);
        log.info("更新任务 {} 的性能指标", taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRiskAssessment(Long taskId, String riskAssessment) {
        postDefenseMapper.updateRiskAssessment(taskId, riskAssessment);
        log.info("更新任务 {} 的风险评估", taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDetectionReport(Long taskId, String detectionReport) {
        postDefenseMapper.updateDetectionReport(taskId, detectionReport);
        log.info("更新任务 {} 的检测报告", taskId);
    }

    @Override
    public Integer getTaskProgress(Long taskId) {
        PostDefense task = findById(taskId);
        return task.getProgress();
    }

    @Override
    public List<PostDefense> findByStatus(String status) {
        return postDefenseMapper.findByStatus(status);
    }

    @Override
    public List<PostDefense> findByInputModelId(Long inputModelId) {
        return postDefenseMapper.findByInputModelId(inputModelId);
    }

    @Override
    public List<PostDefense> findByAlgorithmId(Long algorithmId) {
        return postDefenseMapper.findByAlgorithmId(algorithmId);
    }

    @Override
    public List<PostDefense> findByCreateBy(String createBy) {
        return postDefenseMapper.findByCreateBy(createBy);
    }

    @Override
    public PostDefense findByOutputModelId(Long outputModelId) {
        return postDefenseMapper.findByOutputModelId(outputModelId);
    }

    @Override
    public void validateTask(PostDefense resources) {
        // 基础参数验证（前端已选择，这里做最后校验）
        if (ObjectUtil.isEmpty(resources.getTaskName())) {
            throw new BadRequestException("任务名称不能为空");
        }
        if (ObjectUtil.isNull(resources.getInputModelId())) {
            throw new BadRequestException("输入模型ID不能为空");
        }
        if (ObjectUtil.isNull(resources.getAlgorithmId())) {
            throw new BadRequestException("算法ID不能为空");
        }

        // 自定义参数JSON格式验证
        if (ObjectUtil.isNotEmpty(resources.getCustomParams())) {
            try {
                com.alibaba.fastjson2.JSON.parseObject(resources.getCustomParams());
            } catch (Exception e) {
                throw new BadRequestException("自定义参数格式错误: " + e.getMessage());
            }
        }
    }

    @Override
    public Map<String, Object> getTaskStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // 获取基础统计
        List<Map<String, Object>> taskStats = postDefenseMapper.getTaskStatistics();
        Map<String, Long> statusMap = new HashMap<>();
        for (Map<String, Object> stat : taskStats) {
            statusMap.put((String) stat.get("taskStatus"), ((Number) stat.get("count")).longValue());
        }
        stats.put("statusStats", statusMap);

        // 获取算法类型统计
        List<Map<String, Object>> algorithmStats = postDefenseMapper.getAlgorithmTypeStatistics();
        stats.put("algorithmStats", algorithmStats);

        // 获取防御结果统计
        List<Map<String, Object>> defenseResultStats = postDefenseMapper.getDefenseResultStatistics();
        stats.put("defenseResultStats", defenseResultStats);

        // 总数统计
        PostDefenseQueryCriteria criteria = new PostDefenseQueryCriteria();
        List<PostDefense> allTasks = postDefenseMapper.findAll(criteria);
        stats.put("totalCount", allTasks.size());

        return stats;
    }

    @Override
    public Map<String, Object> getAlgorithmTypeStatistics() {
        Map<String, Object> stats = new HashMap<>();
        List<Map<String, Object>> algorithmStats = postDefenseMapper.getAlgorithmTypeStatistics();
        stats.put("algorithmTypeStats", algorithmStats);
        return stats;
    }

    @Override
    public Map<String, Object> getDefenseResultStatistics() {
        Map<String, Object> stats = new HashMap<>();
        List<Map<String, Object>> defenseResultStats = postDefenseMapper.getDefenseResultStatistics();
        stats.put("defenseResultStats", defenseResultStats);
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
            PostDefense task = findById(taskId);

            // 检查任务状态
            if (!"pending".equals(task.getStatus())) {
                return false;
            }

            // 基本的存在性检查（前端已选择，这里做安全检查）
            return task.getInputModelId() != null && task.getAlgorithmId() != null;

        } catch (Exception e) {
            log.warn("检查任务{}是否可启动时发生异常: {}", taskId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean canDeleteTask(Long taskId) {
        try {
            PostDefense task = findById(taskId);

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