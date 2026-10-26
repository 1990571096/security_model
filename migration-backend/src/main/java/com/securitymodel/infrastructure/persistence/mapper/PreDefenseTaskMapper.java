package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.task.PreDefenseTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PreDefenseTaskMapper extends BaseMapper<PreDefenseTask> {

    @Update("""
            UPDATE sys_pre_defense
            SET status = 'pending',
                progress = 0,
                start_time = NULL,
                end_time = NULL,
                output_dataset_id = NULL,
                cleaning_result = NULL,
                current_run_id = NULL,
                current_attempt = NULL,
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND status = 'failed'
            """)
    int resetForRetry(@Param("taskId") Long taskId);

    @Update("""
            UPDATE sys_pre_defense
            SET status = 'running',
                progress = 1,
                start_time = #{startTime},
                end_time = NULL,
                output_dataset_id = NULL,
                cleaning_result = NULL,
                execution_snapshot = #{executionSnapshot},
                current_run_id = #{runId},
                current_attempt = #{attempt},
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND status = 'pending'
            """)
    int claimForExecution(
            @Param("taskId") Long taskId,
            @Param("runId") String runId,
            @Param("attempt") Integer attempt,
            @Param("startTime") LocalDateTime startTime,
            @Param("executionSnapshot") String executionSnapshot);

    @Update("""
            UPDATE sys_pre_defense
            SET progress = #{progress},
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND current_run_id = #{runId}
              AND status = 'running'
            """)
    int updateRunningProgress(
            @Param("taskId") Long taskId,
            @Param("runId") String runId,
            @Param("progress") Integer progress);

    @Update("""
            UPDATE sys_pre_defense
            SET output_dataset_id = #{outputDatasetId},
                cleaning_result = #{cleaningResult},
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND current_run_id = #{runId}
              AND status = 'running'
            """)
    int updateResultForRun(
            @Param("taskId") Long taskId,
            @Param("runId") String runId,
            @Param("outputDatasetId") Long outputDatasetId,
            @Param("cleaningResult") String cleaningResult);

    @Update("""
            UPDATE sys_pre_defense
            SET status = #{status},
                progress = #{progress},
                end_time = #{endTime},
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND current_run_id = #{runId}
              AND status = 'running'
            """)
    int finishForRun(
            @Param("taskId") Long taskId,
            @Param("runId") String runId,
            @Param("status") String status,
            @Param("progress") Integer progress,
            @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE sys_pre_defense
            SET status = 'canceled',
                cleaning_result = #{cleaningResult},
                end_time = #{endTime},
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND current_run_id = #{runId}
              AND status = 'running'
            """)
    int cancelForRun(
            @Param("taskId") Long taskId,
            @Param("runId") String runId,
            @Param("cleaningResult") String cleaningResult,
            @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE sys_pre_defense
            SET status = 'failed',
                progress = 0,
                cleaning_result = #{failureResult},
                end_time = #{endTime},
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND current_run_id = #{runId}
              AND current_attempt = #{attempt}
              AND status = 'running'
            """)
    int failBeforeExecution(
            @Param("taskId") Long taskId,
            @Param("runId") String runId,
            @Param("attempt") Integer attempt,
            @Param("failureResult") String failureResult,
            @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE sys_pre_defense
            SET status = 'failed',
                progress = 0,
                cleaning_result = #{cleaningResult},
                end_time = #{endTime},
                update_time = NOW()
            WHERE task_id = #{taskId}
              AND status = 'running'
              AND (
                  current_run_id = #{runId}
                  OR (current_run_id IS NULL AND #{runId} IS NULL)
              )
            """)
    int failOrphanedForRun(
            @Param("taskId") Long taskId,
            @Param("runId") String runId,
            @Param("cleaningResult") String cleaningResult,
            @Param("endTime") LocalDateTime endTime);
}
