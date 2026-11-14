package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.assistant.AssistantSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AssistantSessionMapper extends BaseMapper<AssistantSession> {

    @Select("""
            SELECT * FROM sys_assistant_session
            WHERE session_id = #{sessionId} AND user_id = #{userId}
              AND status = 'active' AND expires_at > #{now}
            """)
    AssistantSession findOwned(@Param("sessionId") Long sessionId,
                               @Param("userId") Long userId,
                               @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM sys_assistant_session
            WHERE user_id = #{userId} AND status = 'active' AND expires_at > #{now}
            ORDER BY updated_at DESC
            LIMIT #{limit}
            """)
    List<AssistantSession> findByUser(@Param("userId") Long userId,
                                      @Param("now") LocalDateTime now,
                                      @Param("limit") int limit);

    @Update("""
            UPDATE sys_assistant_session
            SET status = 'deleted', updated_at = #{now}
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND status = 'active'
            """)
    int softDelete(@Param("sessionId") Long sessionId,
                   @Param("userId") Long userId,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_assistant_session
            SET status = 'deleted', updated_at = #{now}
            WHERE status = 'active' AND expires_at <= #{now}
            LIMIT #{limit}
            """)
    int deleteExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
