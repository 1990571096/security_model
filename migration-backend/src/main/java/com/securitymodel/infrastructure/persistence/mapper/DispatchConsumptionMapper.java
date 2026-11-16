package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.task.DispatchConsumption;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DispatchConsumptionMapper extends BaseMapper<DispatchConsumption> {

    @Insert("""
            INSERT INTO sys_dispatch_consumption
              (message_id, task_id, run_id, attempt, worker_id, status,
               claim_owner, claim_until, claim_token, created_at, updated_at)
            VALUES
              (#{messageId}, #{taskId}, #{runId}, #{attempt}, #{workerId}, 'processing',
               #{claimOwner}, #{claimUntil}, #{claimToken}, #{now}, #{now})
            """)
    int insertProcessing(@Param("messageId") String messageId,
                         @Param("taskId") Long taskId,
                         @Param("runId") String runId,
                         @Param("attempt") Integer attempt,
                         @Param("workerId") String workerId,
                         @Param("claimOwner") String claimOwner,
                         @Param("claimUntil") LocalDateTime claimUntil,
                         @Param("claimToken") String claimToken,
                         @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM sys_dispatch_consumption WHERE message_id = #{messageId}
            """)
    DispatchConsumption findByMessageId(@Param("messageId") String messageId);

    @Select("""
            SELECT * FROM sys_dispatch_consumption
            WHERE task_id = #{taskId} AND run_id = #{runId} AND attempt = #{attempt}
              AND status IN ('processing', 'retry')
            ORDER BY consumption_id DESC LIMIT 1
            """)
    DispatchConsumption findUnfinishedByRun(@Param("taskId") Long taskId,
                                            @Param("runId") String runId,
                                            @Param("attempt") Integer attempt);

    @Update("""
            UPDATE sys_dispatch_consumption
            SET claim_owner = #{claimOwner}, claim_until = #{claimUntil}, claim_token = #{claimToken},
                worker_id = #{workerId}, status = 'processing', updated_at = #{now}
            WHERE message_id = #{messageId} AND status IN ('processing', 'retry')
              AND (claim_until IS NULL OR claim_until < #{now})
            """)
    int reclaimExpired(@Param("messageId") String messageId,
                       @Param("claimOwner") String claimOwner,
                       @Param("workerId") String workerId,
                       @Param("claimUntil") LocalDateTime claimUntil,
                       @Param("claimToken") String claimToken,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dispatch_consumption
            SET status = 'retry', outcome = 'retry', error_code = #{errorCode},
                claim_owner = NULL, claim_until = NULL, claim_token = NULL, updated_at = #{now}
            WHERE message_id = #{messageId} AND status = 'processing' AND claim_token = #{claimToken}
            """)
    int releaseForRetry(@Param("messageId") String messageId,
                        @Param("claimToken") String claimToken,
                        @Param("errorCode") String errorCode,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dispatch_consumption
            SET status = 'terminal', outcome = #{outcome}, error_code = #{errorCode},
                terminal_at = #{now}, updated_at = #{now}
            WHERE message_id = #{messageId} AND status = 'processing' AND claim_token = #{claimToken}
            """)
    int markTerminal(@Param("messageId") String messageId,
                     @Param("claimToken") String claimToken,
                     @Param("outcome") String outcome,
                     @Param("errorCode") String errorCode,
                     @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM sys_dispatch_consumption
            WHERE status = 'processing' AND claim_until < #{now}
            """)
    long countExpiredProcessing(@Param("now") LocalDateTime now);
}
