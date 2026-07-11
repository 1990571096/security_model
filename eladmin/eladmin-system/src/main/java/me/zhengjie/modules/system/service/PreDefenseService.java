package me.zhengjie.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.domain.dto.PreDefenseQueryCriteria;
import me.zhengjie.utils.PageResult;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 训练前防御任务服务接口
 * @author ZJING
 * @date 2025-07-30
 */
public interface PreDefenseService extends IService<PreDefense> {

    /**
     * 根据ID查询任务
     * @param taskId 任务ID
     * @return 任务信息
     */
    PreDefense findById(Long taskId);

    /**
     * 创建任务
     * @param resources 任务信息
     */
    void create(PreDefense resources);

    /**
     * 更新任务
     * @param resources 任务信息
     */
    void update(PreDefense resources);

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
    PreDefense findByTaskName(String taskName);

    /**
     * 分页查询任务
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    PageResult<PreDefense> queryAll(PreDefenseQueryCriteria criteria, Page<Object> page);

    /**
     * 查询所有任务（不分页）
     * @param criteria 查询条件
     * @return 任务列表
     */
    List<PreDefense> queryAll(PreDefenseQueryCriteria criteria);

    /**
     * 导出任务数据
     * @param tasks 待导出的数据
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    void download(List<PreDefense> tasks, HttpServletResponse response) throws IOException;

    /**
     * 启动清洗任务
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
     * 更新任务结果
     * @param taskId 任务ID
     * @param outputDatasetId 输出数据集ID
     * @param cleaningResult 清洗结果JSON
     */
    void updateTaskResult(Long taskId, Long outputDatasetId, String cleaningResult);

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
    List<PreDefense> findByStatus(String status);

    /**
     * 验证任务参数
     * @param resources 任务信息
     */
    void validateTask(PreDefense resources);

    // 新增缺失的方法

    /**
     * 根据数据集ID查询任务
     * @param datasetId 数据集ID
     * @return 任务列表
     */
    List<PreDefense> findByDatasetId(Long datasetId);

    /**
     * 根据算法ID查询任务
     * @param algorithmId 算法ID
     * @return 任务列表
     */
    List<PreDefense> findByAlgorithmId(Long algorithmId);

    // 新增统计相关方法

    /**
     * 获取清洗效果统计
     * @return 清洗效果统计数据
     */
    Map<String, Object> getCleaningEffectStatistics();

    /**
     * 获取算法使用统计
     * @return 算法使用统计数据
     */
    List<Map<String, Object>> getAlgorithmUsageStatistics();

    /**
     * 获取数据集使用统计
     * @return 数据集使用统计数据
     */
    List<Map<String, Object>> getDatasetUsageStatistics();

    /**
     * 获取时间趋势统计
     * @return 时间趋势统计数据
     */
    List<Map<String, Object>> getTimeTrendStatistics();


}