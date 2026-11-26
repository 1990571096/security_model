package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.assistant.AssistantConfirmation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AssistantConfirmationMapper extends BaseMapper<AssistantConfirmation> {

    @Select("""
            SELECT * FROM sys_assistant_confirmation
            WHERE confirmation_id = #{confirmationId}
            """)
    AssistantConfirmation findById(@Param("confirmationId") Long confirmationId);

    @Update("""
            UPDATE sys_assistant_confirmation
            SET status = 'consuming'
            WHERE confirmation_id = #{confirmationId}
              AND token_hash = #{tokenHash}
              AND user_id = #{userId}
              AND status = 'pending'
              AND expires_at > #{now}
            """)
    int consume(@Param("confirmationId") Long confirmationId,
                @Param("tokenHash") String tokenHash,
                @Param("userId") Long userId,
                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_assistant_confirmation
            SET status = 'consumed', result_code = #{resultCode},
                result_json = #{resultJson}, consumed_at = #{now}
            WHERE confirmation_id = #{confirmationId} AND status = 'consuming'
            """)
    int markConsumed(@Param("confirmationId") Long confirmationId,
                     @Param("resultCode") Integer resultCode,
                     @Param("resultJson") String resultJson,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_assistant_confirmation
            SET status = 'expired'
            WHERE confirmation_id = #{confirmationId}
              AND status = 'pending' AND expires_at <= #{now}
            """)
    int expire(@Param("confirmationId") Long confirmationId,
               @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM sys_assistant_confirmation
            WHERE user_id = #{userId} AND status IN ('pending', 'consuming')
              AND expires_at > #{now}
            """)
    long countActive(@Param("userId") Long userId,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_assistant_confirmation
            SET status = 'expired'
            WHERE status = 'pending' AND expires_at <= #{now}
            LIMIT #{limit}
            """)
    int expireBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            DELETE FROM sys_assistant_confirmation
            WHERE status = 'expired' AND expires_at < #{cutoff}
            """)
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
