package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.task.PreDefenseTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface PreDefenseTaskMapper extends BaseMapper<PreDefenseTask> {

    /** Serializes edit/delete/start/retry on the same task inside one transaction. */
    @Select("""
            SELECT *
            FROM sys_pre_defense
            WHERE task_id = #{taskId}
            FOR UPDATE
            """)
    PreDefenseTask selectByIdForUpdate(@Param("taskId") Long taskId);

    /** Locks a batch in task-id order to avoid deadlocks between concurrent deletes. */
    @Select("""
            <script>
            SELECT *
            FROM sys_pre_defense
            WHERE task_id IN
            <foreach collection="taskIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY task_id
            FOR UPDATE
            </script>
            """)
    List<PreDefenseTask> selectBatchForUpdate(@Param("taskIds") Collection<Long> taskIds);

    /** Updates only editable business fields and never changes status/run identity. */
    @Update("""
            UPDATE sys_pre_defense
            SET task_name = #{task.taskName},
                description = #{task.description},
                dataset_id = #{task.datasetId},
                algorithm_id = #{task.algorithmId},
                hyper_id = #{task.hyperId},
                custom_params = #{task.customParams},
                update_by = #{task.updateBy},
                update_time = #{task.updateTime}
            WHERE task_id = #{task.taskId}
              AND status = 'pending'
            """)
    int updatePendingFields(@Param("task") PreDefenseTask task);

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
