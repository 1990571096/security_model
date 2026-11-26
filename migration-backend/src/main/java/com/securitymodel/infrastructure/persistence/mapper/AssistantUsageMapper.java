package com.securitymodel.infrastructure.persistence.mapper;

import com.securitymodel.domain.assistant.AssistantUsageDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface AssistantUsageMapper {

    @Update("""
            UPDATE sys_assistant_usage_daily
            SET request_count = request_count + 1, updated_at = #{now}
            WHERE user_id = #{userId} AND usage_date = #{usageDate}
              AND request_count < #{limit}
            """)
    int incrementIfBelow(@Param("userId") Long userId,
                         @Param("usageDate") LocalDate usageDate,
                         @Param("limit") int limit,
                         @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO sys_assistant_usage_daily
                (user_id, usage_date, request_count, updated_at)
            VALUES (#{userId}, #{usageDate}, 1, #{now})
            """)
    int insertFirst(@Param("userId") Long userId,
                    @Param("usageDate") LocalDate usageDate,
                    @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM sys_assistant_usage_daily
            WHERE user_id = #{userId} AND usage_date = #{usageDate}
            """)
    AssistantUsageDaily find(@Param("userId") Long userId,
                             @Param("usageDate") LocalDate usageDate);
}
