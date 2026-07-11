package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.dto.DuringDefenseQueryCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 训练中防御任务 Mapper
 * @author ZJING
 * @date 2025-08-04
 */
@Mapper
public interface DuringDefenseMapper extends BaseMapper<DuringDefense> {

    /**
     * 查询任务总数
     * @param criteria 查询条件
     * @return 总数
     */
    Long countAll(@Param("criteria") DuringDefenseQueryCriteria criteria);

    /**
     * 查询所有任务
     * @param criteria 查询条件
     * @return 任务列表
     */
    List<DuringDefense> findAll(@Param("criteria") DuringDefenseQueryCriteria criteria);

    /**
     * 分页查询任务
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<DuringDefense> findAll(@Param("criteria") DuringDefenseQueryCriteria criteria, Page<Object> page);

    /**
     * 根据任务名称查询
     * @param taskName 任务名称
     * @return 任务信息
     */
    DuringDefense findByTaskName(@Param("taskName") String taskName);

    /**
     * 根据状态查询
     * @param status 任务状态
     * @return 任务列表
     */
    List<DuringDefense> findByStatus(@Param("status") String status);

    /**
     * 根据数据集ID查询
     * @param datasetId 数据集ID
     * @return 任务列表
     */
    List<DuringDefense> findByDatasetId(@Param("datasetId") Long datasetId);

    /**
     * 根据输入模型ID查询
     * @param inputModelId 输入模型ID
     * @return 任务列表
     */
    List<DuringDefense> findByInputModelId(@Param("inputModelId") Long inputModelId);

    /**
     * 根据算法ID查询
     * @param algorithmId 算法ID
     * @return 任务列表
     */
    List<DuringDefense> findByAlgorithmId(@Param("algorithmId") Long algorithmId);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 任务列表
     */
    List<DuringDefense> findByCreateBy(@Param("createBy") String createBy);

    /**
     * 更新任务状态
     * @param taskId 任务ID
     * @param status 新状态
     * @param progress 进度
     */
    void updateStatus(@Param("taskId") Long taskId, @Param("status") String status, @Param("progress") Integer progress);

    /**
     * 更新训练结果
     * @param taskId 任务ID
     * @param outputModelId 输出模型ID
     * @param trainingLog 训练日志
     */
    void updateTrainingResult(@Param("taskId") Long taskId, @Param("outputModelId") Long outputModelId, 
                             @Param("trainingLog") String trainingLog);

    /**
     * 更新训练日志
     * @param taskId 任务ID
     * @param trainingLog 训练日志
     */
    void updateTrainingLog(@Param("taskId") Long taskId, @Param("trainingLog") String trainingLog);

    /**
     * 根据输出模型ID查询任务
     * @param outputModelId 输出模型ID
     * @return 任务信息
     */
    DuringDefense findByOutputModelId(@Param("outputModelId") Long outputModelId);

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
}