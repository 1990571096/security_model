package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.task.TaskDispatchIntent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskDispatchIntentMapper extends BaseMapper<TaskDispatchIntent> {

    @Select("""
            SELECT * FROM sys_task_dispatch_intent
            WHERE task_id = #{taskId} AND run_id = #{runId} AND attempt = #{attempt}
            """)
    TaskDispatchIntent findByRun(@Param("taskId") Long taskId,
                                 @Param("runId") String runId,
                                 @Param("attempt") Integer attempt);

    @Update("""
            UPDATE sys_task_dispatch_intent
            SET status = 'submitting', updated_at = #{now}
            WHERE task_id = #{taskId} AND run_id = #{runId} AND attempt = #{attempt}
              AND status = 'pending'
            """)
    int markSubmitting(@Param("taskId") Long taskId,
                       @Param("runId") String runId,
                       @Param("attempt") Integer attempt,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_task_dispatch_intent
            SET status = 'submitted', submitted_at = #{now}, updated_at = #{now}
            WHERE task_id = #{taskId} AND run_id = #{runId} AND attempt = #{attempt}
              AND status = 'submitting'
            """)
    int markSubmitted(@Param("taskId") Long taskId,
                      @Param("runId") String runId,
                      @Param("attempt") Integer attempt,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_task_dispatch_intent
            SET status = 'abandoned', error_code = #{errorCode}, updated_at = #{now}
            WHERE task_id = #{taskId} AND run_id = #{runId} AND attempt = #{attempt}
              AND status IN ('pending', 'submitting')
            """)
    int abandon(@Param("taskId") Long taskId,
                @Param("runId") String runId,
                @Param("attempt") Integer attempt,
                @Param("errorCode") String errorCode,
                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_task_dispatch_intent
            SET status = 'abandoned', error_code = #{errorCode}, updated_at = #{now}
            WHERE task_id = #{taskId} AND run_id = #{runId} AND attempt = #{attempt}
              AND status = 'pending'
            """)
    int abandonPending(@Param("taskId") Long taskId,
                       @Param("runId") String runId,
                       @Param("attempt") Integer attempt,
                       @Param("errorCode") String errorCode,
                       @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM sys_task_dispatch_intent
            WHERE status = 'pending' AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<TaskDispatchIntent> findPendingOlderThan(@Param("cutoff") LocalDateTime cutoff,
                                                  @Param("limit") int limit);

    @Select("""
            SELECT * FROM sys_task_dispatch_intent
            WHERE status = 'submitting' AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<TaskDispatchIntent> findSubmittingOlderThan(@Param("cutoff") LocalDateTime cutoff,
                                                     @Param("limit") int limit);
}
