package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import me.zhengjie.modules.system.domain.PostDefense;
import me.zhengjie.modules.system.domain.dto.PostDefenseQueryCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 训练后防御任务 Mapper
 * @author ZJING
 * @date 2025-08-08
 */
@Mapper
public interface PostDefenseMapper extends BaseMapper<PostDefense> {

    /**
     * 查询任务总数
     * @param criteria 查询条件
     * @return 总数
     */
    Long countAll(@Param("criteria") PostDefenseQueryCriteria criteria);

    /**
     * 查询所有任务
     * @param criteria 查询条件
     * @return 任务列表
     */
    List<PostDefense> findAll(@Param("criteria") PostDefenseQueryCriteria criteria);

    /**
     * 分页查询任务
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<PostDefense> findAll(@Param("criteria") PostDefenseQueryCriteria criteria, Page<Object> page);

    /**
     * 根据任务名称查询
     * @param taskName 任务名称
     * @return 任务信息
     */
    PostDefense findByTaskName(@Param("taskName") String taskName);

    /**
     * 根据状态查询
     * @param status 任务状态
     * @return 任务列表
     */
    List<PostDefense> findByStatus(@Param("status") String status);

    /**
     * 根据输入模型ID查询
     * @param inputModelId 输入模型ID
     * @return 任务列表
     */
    List<PostDefense> findByInputModelId(@Param("inputModelId") Long inputModelId);

    /**
     * 根据算法ID查询
     * @param algorithmId 算法ID
     * @return 任务列表
     */
    List<PostDefense> findByAlgorithmId(@Param("algorithmId") Long algorithmId);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 任务列表
     */
    List<PostDefense> findByCreateBy(@Param("createBy") String createBy);

    /**
     * 更新任务状态
     * @param taskId 任务ID
     * @param status 新状态
     * @param progress 进度
     */
    void updateStatus(@Param("taskId") Long taskId, @Param("status") String status, @Param("progress") Integer progress);

    /**
     * 更新防御结果
     * @param taskId 任务ID
     * @param defenseResult 防御结果
     * @param outputModelId 输出模型ID
     * @param outputModelPath 输出模型路径
     */
    void updateDefenseResult(@Param("taskId") Long taskId, 
                            @Param("defenseResult") String defenseResult, 
                            @Param("outputModelId") Long outputModelId,
                            @Param("outputModelPath") String outputModelPath);

    /**
     * 更新性能指标
     * @param taskId 任务ID
     * @param performanceMetrics 性能指标
     */
    void updatePerformanceMetrics(@Param("taskId") Long taskId, @Param("performanceMetrics") String performanceMetrics);

    /**
     * 更新风险评估
     * @param taskId 任务ID
     * @param riskAssessment 风险评估
     */
    void updateRiskAssessment(@Param("taskId") Long taskId, @Param("riskAssessment") String riskAssessment);

    /**
     * 更新检测报告
     * @param taskId 任务ID
     * @param detectionReport 检测报告
     */
    void updateDetectionReport(@Param("taskId") Long taskId, @Param("detectionReport") String detectionReport);

    /**
     * 根据输出模型ID查询任务
     * @param outputModelId 输出模型ID
     * @return 任务信息
     */
    PostDefense findByOutputModelId(@Param("outputModelId") Long outputModelId);

    /**
     * 获取任务统计信息
     * @return 统计结果
     */
    List<java.util.Map<String, Object>> getTaskStatistics();

    /**
     * 根据算法类型统计任务数量
     * @return 统计结果
     */
    List<java.util.Map<String, Object>> getAlgorithmTypeStatistics();

    /**
     * 根据防御结果统计
     * @return 统计结果
     */
    List<java.util.Map<String, Object>> getDefenseResultStatistics();
}