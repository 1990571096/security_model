package com.securitymodel.domain.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_task_dispatch_intent")
public class TaskDispatchIntent {

    public static final String PENDING = "pending";
    public static final String SUBMITTING = "submitting";
    public static final String SUBMITTED = "submitted";
    public static final String ABANDONED = "abandoned";

    @TableId(value = "intent_id", type = IdType.AUTO)
    private Long intentId;
    @TableField("task_id")
    private Long taskId;
    @TableField("run_id")
    private String runId;
    private Integer attempt;
    private String status;
    @TableField("schema_version")
    private Integer schemaVersion;
    @TableField("request_json")
    private String requestJson;
    @TableField("request_hash")
    private String requestHash;
    @TableField("runtime_hash")
    private String runtimeHash;
    @TableField("error_code")
    private String errorCode;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @TableField("submitted_at")
    private LocalDateTime submittedAt;
}
