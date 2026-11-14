package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.assistant.AssistantToolAudit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AssistantToolAuditMapper extends BaseMapper<AssistantToolAudit> {

    @Select("""
            SELECT * FROM sys_assistant_tool_audit
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, audit_id DESC
            LIMIT #{limit}
            """)
    List<AssistantToolAudit> findByUser(@Param("userId") Long userId,
                                        @Param("limit") int limit);

    @Select("""
            SELECT * FROM sys_assistant_tool_audit
            WHERE target_task_id = #{taskId}
            ORDER BY created_at DESC, audit_id DESC
            LIMIT #{limit}
            """)
    List<AssistantToolAudit> findByTask(@Param("taskId") Long taskId,
                                        @Param("limit") int limit);

    @Delete("""
            DELETE FROM sys_assistant_tool_audit
            WHERE created_at < #{cutoff}
            """)
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
