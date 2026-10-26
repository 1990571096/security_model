package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.audit.OperationAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OperationAuditMapper extends BaseMapper<OperationAudit> {

    @Select("""
            SELECT *
            FROM security_operation_audit
            WHERE (
                resource_type = 'pre_defense_task'
                AND resource_id = #{taskId}
              )
              OR (
                resource_type = 'clean_subset_export'
                AND resource_id IN (
                    SELECT export_id
                    FROM sys_pre_defense_export
                    WHERE task_id = #{taskId}
                )
              )
            ORDER BY created_time DESC, audit_id DESC
            """)
    List<OperationAudit> findTaskAudits(Long taskId);
}
