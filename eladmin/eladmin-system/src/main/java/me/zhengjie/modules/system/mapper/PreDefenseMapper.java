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
import java.time.LocalDateTime;

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
     * 统计仍被任务引用的数据集数量。
     */
    long countByDatasetId(@Param("datasetId") Long datasetId);

    /**
     * 统计仍被任务引用的算法数量。
     */
    long countByAlgorithmId(@Param("algorithmId") Long algorithmId);

    /**
     * 统计仍被任务引用的超参数模板数量。
     */
    long countByHyperId(@Param("hyperId") Long hyperId);

    /**
     * 原子领取待执行任务，防止重复点击或并发请求启动多个执行器。
     */
    int claimForExecution(@Param("taskId") Long taskId,
                          @Param("runId") String runId,
                          @Param("attempt") Integer attempt,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("executionSnapshot") String executionSnapshot);

    /**
     * 仅允许当前运行令牌更新执行进度。
     */
    int updateRunningProgress(@Param("taskId") Long taskId,
                              @Param("runId") String runId,
                              @Param("progress") Integer progress);

    /**
     * 仅允许当前运行令牌写入执行结果。
     */
    int updateResultForRun(@Param("taskId") Long taskId,
                           @Param("runId") String runId,
                           @Param("outputDatasetId") Long outputDatasetId,
                           @Param("cleaningResult") String cleaningResult);

    /**
     * 仅允许当前运行令牌结束任务。
     */
    int finishForRun(@Param("taskId") Long taskId,
                     @Param("runId") String runId,
                     @Param("status") String status,
                     @Param("progress") Integer progress,
                     @Param("endTime") LocalDateTime endTime);

    /**
     * Atomically cancel the current running attempt.
     */
    int cancelForRun(@Param("taskId") Long taskId,
                     @Param("runId") String runId,
                     @Param("cleaningResult") String cleaningResult,
                     @Param("endTime") LocalDateTime endTime);

    /**
     * 外部执行器尚未启动时，将当前运行任务收敛为失败。
     */
    int failBeforeExecution(@Param("taskId") Long taskId,
                            @Param("runId") String runId,
                            @Param("attempt") Integer attempt,
                            @Param("failureResult") String failureResult,
                            @Param("endTime") LocalDateTime endTime);

    /**
     * 将应用重启后遗留的运行中任务收敛为失败。
     *
     * <p>更新条件包含当前运行标识，避免多实例或并发重试时误伤新执行。</p>
     */
    int failOrphanedForRun(@Param("taskId") Long taskId,
                           @Param("runId") String runId,
                           @Param("cleaningResult") String cleaningResult,
                           @Param("endTime") LocalDateTime endTime);

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
