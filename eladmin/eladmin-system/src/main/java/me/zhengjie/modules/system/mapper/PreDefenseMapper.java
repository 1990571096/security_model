package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.domain.dto.PreDefenseQueryCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 训练前防御任务 Mapper
 * @author ZJING
 * @date 2025-07-30
 */
@Mapper
public interface PreDefenseMapper extends BaseMapper<PreDefense> {

    /**
     * 查询任务总数
     * @param criteria 查询条件
     * @return 总数
     */
    Long countAll(@Param("criteria") PreDefenseQueryCriteria criteria);

    /**
     * 查询所有任务
     * @param criteria 查询条件
     * @return 任务列表
     */
    List<PreDefense> findAll(@Param("criteria") PreDefenseQueryCriteria criteria);

    /**
     * 分页查询任务
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<PreDefense> findAll(@Param("criteria") PreDefenseQueryCriteria criteria, Page<Object> page);

    /**
     * 根据任务名称查询
     * @param taskName 任务名称
     * @return 任务信息
     */
    PreDefense findByTaskName(@Param("taskName") String taskName);

    /**
     * 根据状态查询
     * @param status 任务状态
     * @return 任务列表
     */
    List<PreDefense> findByStatus(@Param("status") String status);

    /**
     * 根据数据集ID查询
     * @param datasetId 数据集ID
     * @return 任务列表
     */
    List<PreDefense> findByDatasetId(@Param("datasetId") Long datasetId);

    /**
     * 根据算法ID查询
     * @param algorithmId 算法ID
     * @return 任务列表
     */
    List<PreDefense> findByAlgorithmId(@Param("algorithmId") Long algorithmId);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 任务列表
     */
    List<PreDefense> findByCreateBy(@Param("createBy") String createBy);

    /**
     * 更新任务状态
     * @param taskId 任务ID
     * @param status 新状态
     * @param progress 进度
     */
    void updateStatus(@Param("taskId") Long taskId, @Param("status") String status, @Param("progress") Integer progress);

    /**
     * 更新任务结果
     * @param taskId 任务ID
     * @param outputDatasetId 输出数据集ID
     * @param cleaningResult 清洗结果JSON
     */
    void updateResult(@Param("taskId") Long taskId,
                      @Param("outputDatasetId") Long outputDatasetId,
                      @Param("cleaningResult") String cleaningResult);

    /**
     * 将失败任务恢复为待启动状态，同时清除上一次运行结果。
     * @return 成功更新的记录数
     */
    int resetForRetry(@Param("taskId") Long taskId);

    // 新增统计相关方法

    /**
     * 获取清洗效果统计
     * @return 清洗效果统计数据
     */
    Map<String, Object> getCleaningEffectStatistics();

    /**
     * 获取算法使用统计
     * @return 算法使用统计数据列表
     */
    List<Map<String, Object>> getAlgorithmUsageStatistics();

    /**
     * 获取数据集使用统计
     * @return 数据集使用统计数据列表
     */
    List<Map<String, Object>> getDatasetUsageStatistics();

    /**
     * 获取时间趋势统计
     * @return 时间趋势统计数据列表
     */
    List<Map<String, Object>> getTimeTrendStatistics();
}
