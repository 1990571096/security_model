package com.securitymodel.domain.audit;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("security_operation_audit")
public class OperationAudit {

    @TableId(value = "audit_id", type = IdType.AUTO)
    private Long auditId;
    @TableField("resource_type")
    private String resourceType;
    @TableField("resource_id")
    private Long resourceId;
    private String action;
    private String operator;
    @TableField("before_status")
    private String beforeStatus;
    @TableField("after_status")
    private String afterStatus;
    @TableField("request_snapshot")
    private String requestSnapshot;
    @TableField("result_message")
    private String resultMessage;
    @TableField("created_time")
    private LocalDateTime createdTime;
}
