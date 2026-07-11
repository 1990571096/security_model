package me.zhengjie.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import me.zhengjie.modules.system.domain.PostDefense;
import me.zhengjie.modules.system.domain.dto.PostDefenseQueryCriteria;
import me.zhengjie.utils.PageResult;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 训练后防御任务服务接口
 * @author ZJING
 * @date 2025-08-08
 */
public interface PostDefenseService extends IService<PostDefense> {

    /**
     * 根据ID查询任务
     * @param taskId 任务ID
     * @return 任务信息
     */
    PostDefense findById(Long taskId);

    /**
     * 创建任务
     * @param resources 任务信息
     */
    void create(PostDefense resources);

    /**
     * 更新任务
     * @param resources 任务信息
     */
    void update(PostDefense resources);

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
    PostDefense findByTaskName(String taskName);

    /**
     * 分页查询任务
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    PageResult<PostDefense> queryAll(PostDefenseQueryCriteria criteria, Page<Object> page);

    /**
     * 查询所有任务（不分页）
     * @param criteria 查询条件
     * @return 任务列表
     */
    List<PostDefense> queryAll(PostDefenseQueryCriteria criteria);

    /**
     * 导出任务数据
     * @param tasks 待导出的数据
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    void download(List<PostDefense> tasks, HttpServletResponse response) throws IOException;

    /**
     * 启动防御任务
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
     * 更新防御结果
     * @param taskId 任务ID
     * @param defenseResult 防御结果
     * @param outputModelId 输出模型ID
     * @param outputModelPath 输出模型路径
     */
    void updateDefenseResult(Long taskId, String defenseResult, Long outputModelId, String outputModelPath);

    /**
     * 更新性能指标
     * @param taskId 任务ID
     * @param performanceMetrics 性能指标
     */
    void updatePerformanceMetrics(Long taskId, String performanceMetrics);

    /**
     * 更新风险评估
     * @param taskId 任务ID
     * @param riskAssessment 风险评估
     */
    void updateRiskAssessment(Long taskId, String riskAssessment);

    /**
     * 更新检测报告
     * @param taskId 任务ID
     * @param detectionReport 检测报告
     */
    void updateDetectionReport(Long taskId, String detectionReport);

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
    List<PostDefense> findByStatus(String status);

    /**
     * 根据输入模型ID查询任务
     * @param inputModelId 输入模型ID
     * @return 任务列表
     */
    List<PostDefense> findByInputModelId(Long inputModelId);

    /**
     * 根据算法ID查询任务
     * @param algorithmId 算法ID
     * @return 任务列表
     */
    List<PostDefense> findByAlgorithmId(Long algorithmId);

    /**
     * 根据创建人查询任务
     * @param createBy 创建人
     * @return 任务列表
     */
    List<PostDefense> findByCreateBy(String createBy);

    /**
     * 根据输出模型ID查询任务
     * @param outputModelId 输出模型ID
     * @return 任务信息
     */
    PostDefense findByOutputModelId(Long outputModelId);

    /**
     * 验证任务参数
     * @param resources 任务信息
     */
    void validateTask(PostDefense resources);

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
     * 获取防御结果统计
     * @return 统计信息
     */
    java.util.Map<String, Object> getDefenseResultStatistics();

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