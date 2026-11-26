package com.securitymodel.domain.assistant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Tool-level audit: name, operator, target, result and duration only. */
@Data
@TableName("sys_assistant_tool_audit")
public class AssistantToolAudit {

    @TableId(value = "audit_id", type = IdType.AUTO)
    private Long auditId;
    private Long sessionId;
    private Long userId;
    private String toolName;
    private String operation;
    private Long targetTaskId;
    private String targetRunId;
    private String resultCode;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
