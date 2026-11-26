package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.task.DispatchOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DispatchOutboxMapper extends BaseMapper<DispatchOutbox> {

    @Select("""
            SELECT * FROM sys_dispatch_outbox
            WHERE task_id = #{taskId} AND run_id = #{runId} AND attempt = #{attempt}
              AND event_type = #{eventType}
            """)
    DispatchOutbox findByRun(@Param("taskId") Long taskId,
                             @Param("runId") String runId,
                             @Param("attempt") Integer attempt,
                             @Param("eventType") String eventType);

    @Select("""
            SELECT * FROM sys_dispatch_outbox
            WHERE status IN ('new', 'failed') AND next_at <= #{now}
            ORDER BY outbox_id ASC
            LIMIT #{limit}
            """)
    List<DispatchOutbox> findClaimCandidates(@Param("now") LocalDateTime now,
                                             @Param("limit") int limit);

    @Update("""
            UPDATE sys_dispatch_outbox
            SET status = 'publishing', publish_attempts = publish_attempts + 1,
                claim_owner = #{owner}, claim_token = #{claimToken}, claim_until = #{claimUntil},
                updated_at = #{now}
            WHERE outbox_id = #{outboxId} AND status IN ('new', 'failed') AND next_at <= #{now}
            """)
    int markPublishing(@Param("outboxId") Long outboxId,
                       @Param("owner") String owner,
                       @Param("claimToken") String claimToken,
                       @Param("claimUntil") LocalDateTime claimUntil,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dispatch_outbox
            SET status = 'published', published_at = #{now}, updated_at = #{now},
                claim_owner = NULL, claim_until = NULL, claim_token = NULL, last_error = NULL
            WHERE outbox_id = #{outboxId} AND status = 'publishing' AND claim_token = #{claimToken}
            """)
    int markPublished(@Param("outboxId") Long outboxId,
                      @Param("claimToken") String claimToken,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dispatch_outbox
            SET status = 'failed', next_at = #{nextAt}, updated_at = #{now},
                claim_owner = NULL, claim_until = NULL, claim_token = NULL,
                last_error = #{error}
            WHERE outbox_id = #{outboxId} AND status = 'publishing' AND claim_token = #{claimToken}
            """)
    int markRetry(@Param("outboxId") Long outboxId,
                  @Param("claimToken") String claimToken,
                  @Param("nextAt") LocalDateTime nextAt,
                  @Param("error") String error,
                  @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dispatch_outbox
            SET status = 'dead', updated_at = #{now},
                claim_owner = NULL, claim_until = NULL, claim_token = NULL,
                last_error = #{error}
            WHERE outbox_id = #{outboxId} AND status = 'publishing' AND claim_token = #{claimToken}
            """)
    int markDead(@Param("outboxId") Long outboxId,
                 @Param("claimToken") String claimToken,
                 @Param("error") String error,
                 @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM sys_dispatch_outbox WHERE message_id = #{messageId}
            """)
    DispatchOutbox findByMessageId(@Param("messageId") String messageId);

    @Select("""
            SELECT * FROM sys_dispatch_outbox WHERE message_id = #{messageId} FOR UPDATE
            """)
    DispatchOutbox findByMessageIdForUpdate(@Param("messageId") String messageId);

    @Update("""
            UPDATE sys_dispatch_outbox
            SET status = 'dead', updated_at = #{now}, last_error = #{error}
            WHERE message_id = #{messageId} AND status <> 'dead'
            """)
    int markDeadByMessageId(@Param("messageId") String messageId,
                            @Param("error") String error,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dispatch_outbox
            SET message_id = #{newMessageId}, payload_json = #{payloadJson},
                payload_hash = #{payloadHash}, status = 'new',
                publish_attempts = 0, next_at = #{now}, updated_at = #{now},
                claim_owner = NULL, claim_until = NULL, claim_token = NULL, last_error = NULL,
                published_at = NULL
            WHERE message_id = #{messageId} AND status = 'dead'
            """)
    int replayDead(@Param("messageId") String messageId,
                   @Param("newMessageId") String newMessageId,
                   @Param("payloadJson") String payloadJson,
                   @Param("payloadHash") String payloadHash,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dispatch_outbox
            SET status = 'failed', next_at = #{now}, updated_at = #{now},
                claim_owner = NULL, claim_until = NULL, claim_token = NULL,
                last_error = 'PUBLISH_CLAIM_EXPIRED'
            WHERE status = 'publishing' AND claim_until < #{now}
            """)
    int reclaimExpired(@Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM sys_dispatch_outbox
            WHERE status = #{status}
            """)
    long countByStatus(@Param("status") String status);

    @Select("""
            SELECT COUNT(*) FROM sys_dispatch_outbox
            WHERE status IN ('new', 'failed') AND next_at <= #{now}
            """)
    long countBacklog(@Param("now") LocalDateTime now);

    @Select("""
            SELECT MIN(created_at) FROM sys_dispatch_outbox
            WHERE status IN ('new', 'failed', 'publishing')
            """)
    LocalDateTime oldestUnpublishedAt();

    @Select("""
            SELECT * FROM sys_dispatch_outbox
            WHERE status = 'dead'
            ORDER BY updated_at DESC
            LIMIT #{limit}
            """)
    List<DispatchOutbox> findDead(@Param("limit") int limit);
}
