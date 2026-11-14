package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.task.RequestIdempotencyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RequestIdempotencyMapper extends BaseMapper<RequestIdempotencyRecord> {

    @Select("""
            SELECT * FROM sys_request_idempotency
            WHERE owner_user_id = #{ownerUserId} AND operation = #{operation}
              AND idempotency_key = #{key}
              AND (expires_at > #{now} OR status IN ('processing', 'failed'))
            """)
    RequestIdempotencyRecord findByKey(@Param("ownerUserId") Long ownerUserId,
                                       @Param("operation") String operation,
                                       @Param("key") String key,
                                       @Param("now") LocalDateTime now);

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM sys_request_idempotency
            WHERE owner_user_id = #{ownerUserId} AND operation = #{operation}
              AND idempotency_key = #{key} AND expires_at <= #{now}
              AND status = 'succeeded'
            """)
    int deleteExpired(@Param("ownerUserId") Long ownerUserId,
                      @Param("operation") String operation,
                      @Param("key") String key,
                      @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM sys_request_idempotency
            WHERE status = 'processing' AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    java.util.List<RequestIdempotencyRecord> findStaleProcessing(@Param("cutoff") LocalDateTime cutoff,
                                                                 @Param("limit") int limit);

    @Update("""
            UPDATE sys_request_idempotency
            SET status = 'failed', response_code = 409,
                response_body = '{"error_code":"IDEMPOTENCY_PROCESS_INTERRUPTED"}',
                updated_at = #{now}
            WHERE record_id = #{recordId} AND status = 'processing'
            """)
    int markStaleProcessingFailed(@Param("recordId") Long recordId,
                                  @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_request_idempotency
            SET status = 'succeeded', response_code = #{code}, response_body = #{body},
                resource_id = #{resourceId}, updated_at = #{now}
            WHERE record_id = #{recordId} AND status = 'processing'
            """)
    int markSucceeded(@Param("recordId") Long recordId,
                      @Param("code") Integer code,
                      @Param("body") String body,
                      @Param("resourceId") Long resourceId,
                      @Param("now") LocalDateTime now);
}
