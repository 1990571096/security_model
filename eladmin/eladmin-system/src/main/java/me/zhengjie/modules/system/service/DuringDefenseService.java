package me.zhengjie.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.dto.DuringDefenseQueryCriteria;
import me.zhengjie.utils.PageResult;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 训练中防御任务服务接口
 * @author ZJING
 * @date 2025-08-04
 */
public interface DuringDefenseService extends IService<DuringDefense> {

    /**
     * 根据ID查询任务
     * @param taskId 任务ID
     * @return 任务信息
     */
    DuringDefense findById(Long taskId);

    /**
     * 创建任务
     * @param resources 任务信息
     */
    void create(DuringDefense resources);

    /**
     * 更新任务
     * @param resources 任务信息
     */
    void update(DuringDefense resources);

    /**
     * 删除任务
     * @param taskIds 任务ID集合
     */
    void delete(Set<Long> taskIds);

    /**
     * 根据任务名称查询
     * @param taskName 任务名称
     * @return 任务信息
     */
    DuringDefense findByTaskName(String taskName);

    /**
     * 分页查询任务
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    PageResult<DuringDefense> queryAll(DuringDefenseQueryCriteria criteria, Page<Object> page);

    /**
     * 查询所有任务（不分页）
     * @param criteria 查询条件
     * @return 任务列表
     */
    List<DuringDefense> queryAll(DuringDefenseQueryCriteria criteria);

    /**
     * 导出任务数据
     * @param tasks 待导出的数据
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    void download(List<DuringDefense> tasks, HttpServletResponse response) throws IOException;

    /**
     * 启动训练任务
     * @param taskId 任务ID
     */
    void startTask(Long taskId);

    /**
     * 更新任务状态
     * @param taskId 任务ID
     * @param status 任务状态
     * @param progress 进度
     */
    void updateTaskStatus(Long taskId, String status, Integer progress);

    /**
     * 更新训练结果
     * @param taskId 任务ID
     * @param outputModelId 输出模型ID
     * @param trainingLog 训练日志
     */
    void updateTrainingResult(Long taskId, Long outputModelId, String trainingLog);

    /**
     * 更新训练日志
     * @param taskId 任务ID
     * @param trainingLog 训练日志
     */
    void updateTrainingLog(Long taskId, String trainingLog);

    /**
     * 获取任务进度
     * @param taskId 任务ID
     * @return 进度百分比
     */
    Integer getTaskProgress(Long taskId);

    /**
     * 根据状态查询任务
     * @param status 任务状态
     * @return 任务列表
     */
    List<DuringDefense> findByStatus(String status);

    /**
     * 根据数据集ID查询任务
     * @param datasetId 数据集ID
     * @return 任务列表
     */
    List<DuringDefense> findByDatasetId(Long datasetId);

    /**
     * 根据输入模型ID查询任务
     * @param inputModelId 输入模型ID
     * @return 任务列表
     */
    List<DuringDefense> findByInputModelId(Long inputModelId);

    /**
     * 根据算法ID查询任务
     * @param algorithmId 算法ID
     * @return 任务列表
     */
    List<DuringDefense> findByAlgorithmId(Long algorithmId);

    /**
     * 根据创建人查询任务
     * @param createBy 创建人
     * @return 任务列表
     */
    List<DuringDefense> findByCreateBy(String createBy);

    /**
     * 根据输出模型ID查询任务
     * @param outputModelId 输出模型ID
     * @return 任务信息
     */
    DuringDefense findByOutputModelId(Long outputModelId);

    /**
     * 验证任务参数
     * @param resources 任务信息
     */
    void validateTask(DuringDefense resources);

    /**
     * 获取任务统计信息
     * @return 统计信息
     */
    java.util.Map<String, Object> getTaskStatistics();

    /**
     * 获取算法类型统计
     * @return 统计信息
     */
    java.util.Map<String, Object> getAlgorithmTypeStatistics();

    /**
     * 批量启动任务
     * @param taskIds 任务ID列表
     * @return 启动结果
     */
    java.util.Map<String, Object> startBatchTasks(Set<Long> taskIds);

    /**
     * 检查任务是否可以启动
     * @param taskId 任务ID
     * @return 是否可以启动
     */
    boolean canStartTask(Long taskId);

    /**
     * 检查任务是否可以删除
     * @param taskId 任务ID
     * @return 是否可以删除
     */
    boolean canDeleteTask(Long taskId);
}