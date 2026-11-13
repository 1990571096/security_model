package com.securitymodel.infrastructure.persistence.mapper;

import com.securitymodel.domain.lease.GpuLease;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Single-slot GPU lease in MySQL. Every acquisition is one conditional UPDATE, so two
 * concurrent starts cannot both win, and an expired lease can be reclaimed without a
 * separate "cleanup" step.
 */
@Mapper
public interface GpuLeaseMapper {

    @Insert("""
            INSERT IGNORE INTO sys_gpu_lease (slot_id, fencing_token)
            VALUES (#{slotId}, 0)
            """)
    int bootstrap(@Param("slotId") String slotId);

    @Update("""
            UPDATE sys_gpu_lease
            SET lease_token = #{token},
                task_id = #{taskId},
                run_id = #{runId},
                attempt = #{attempt},
                acquired_at = #{now},
                heartbeat_at = #{now},
                expires_at = #{expiresAt},
                released_at = NULL,
                fencing_token = fencing_token + 1
            WHERE slot_id = #{slotId}
              AND (lease_token IS NULL OR expires_at < #{now})
              AND blocked_at IS NULL
            """)
    int acquire(@Param("slotId") String slotId,
                @Param("token") String token,
                @Param("taskId") Long taskId,
                @Param("runId") String runId,
                @Param("attempt") Integer attempt,
                @Param("now") LocalDateTime now,
                @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE sys_gpu_lease
            SET heartbeat_at = #{now}, expires_at = #{expiresAt}
            WHERE slot_id = #{slotId}
              AND lease_token = #{token}
              AND released_at IS NULL
            """)
    int renew(@Param("slotId") String slotId,
              @Param("token") String token,
              @Param("now") LocalDateTime now,
              @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE sys_gpu_lease
            SET lease_token = NULL, task_id = NULL, run_id = NULL, attempt = NULL,
                released_at = #{now}
            WHERE slot_id = #{slotId} AND lease_token = #{token}
            """)
    int release(@Param("slotId") String slotId,
                @Param("token") String token,
                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_gpu_lease
            SET lease_token = NULL, task_id = NULL, run_id = NULL, attempt = NULL,
                released_at = #{now}, blocked_at = NULL, blocked_reason = NULL
            WHERE slot_id = #{slotId} AND task_id = #{taskId} AND run_id = #{runId}
            """)
    int releaseByRun(@Param("slotId") String slotId,
                     @Param("taskId") Long taskId,
                     @Param("runId") String runId,
                     @Param("now") LocalDateTime now);

    /**
     * Quarantine the slot. Conditional on the run still owning the row so a later run cannot be
     * blocked by a stale recovery decision.
     */
    @Update("""
            UPDATE sys_gpu_lease
            SET blocked_at = #{now}, blocked_reason = #{reason}
            WHERE slot_id = #{slotId} AND task_id = #{taskId} AND run_id = #{runId}
              AND blocked_at IS NULL
            """)
    int block(@Param("slotId") String slotId,
              @Param("taskId") Long taskId,
              @Param("runId") String runId,
              @Param("reason") String reason,
              @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM sys_gpu_lease WHERE blocked_at IS NOT NULL ORDER BY slot_id
            """)
    @Results(id = "gpuLeaseBlocked", value = {
            @Result(column = "slot_id", property = "slotId"),
            @Result(column = "lease_token", property = "leaseToken"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "attempt", property = "attempt"),
            @Result(column = "fencing_token", property = "fencingToken"),
            @Result(column = "acquired_at", property = "acquiredAt"),
            @Result(column = "heartbeat_at", property = "heartbeatAt"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "released_at", property = "releasedAt"),
            @Result(column = "blocked_at", property = "blockedAt"),
            @Result(column = "blocked_reason", property = "blockedReason")
    })
    List<GpuLease> findBlocked();

    @Select("""
            SELECT * FROM sys_gpu_lease WHERE slot_id = #{slotId}
            """)
    @Results(id = "gpuLease", value = {
            @Result(column = "slot_id", property = "slotId"),
            @Result(column = "lease_token", property = "leaseToken"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "attempt", property = "attempt"),
            @Result(column = "fencing_token", property = "fencingToken"),
            @Result(column = "acquired_at", property = "acquiredAt"),
            @Result(column = "heartbeat_at", property = "heartbeatAt"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "released_at", property = "releasedAt"),
            @Result(column = "blocked_at", property = "blockedAt"),
            @Result(column = "blocked_reason", property = "blockedReason")
    })
    GpuLease findBySlot(@Param("slotId") String slotId);

    @Select("""
            SELECT * FROM sys_gpu_lease
            WHERE lease_token IS NOT NULL AND released_at IS NULL AND expires_at >= #{now}
            ORDER BY slot_id
            """)
    @Results(id = "gpuLeaseActive", value = {
            @Result(column = "slot_id", property = "slotId"),
            @Result(column = "lease_token", property = "leaseToken"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "attempt", property = "attempt"),
            @Result(column = "fencing_token", property = "fencingToken"),
            @Result(column = "acquired_at", property = "acquiredAt"),
            @Result(column = "heartbeat_at", property = "heartbeatAt"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "released_at", property = "releasedAt"),
            @Result(column = "blocked_at", property = "blockedAt"),
            @Result(column = "blocked_reason", property = "blockedReason")
    })
    List<GpuLease> findActive(@Param("now") LocalDateTime now);
}
