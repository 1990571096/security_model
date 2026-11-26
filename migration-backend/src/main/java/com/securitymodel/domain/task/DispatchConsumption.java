package com.securitymodel.domain.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Worker consumption ledger. {@code messageId} is unique; a duplicate delivery can only
 * observe the existing row. A processing row has a bounded claim so a crashed Worker's
 * delivery can be inspected without silently starting Python again.
 */
@Data
@TableName("sys_dispatch_consumption")
public class DispatchConsumption {

    public static final String PROCESSING = "processing";
    public static final String TERMINAL = "terminal";
    public static final String RETRY = "retry";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILED = "failed";
    public static final String OUTCOME_CANCELED = "canceled";
    public static final String OUTCOME_DUPLICATE = "duplicate";
    public static final String OUTCOME_STALE = "stale";
    public static final String OUTCOME_UNCERTAIN = "uncertain";

    @TableId(value = "consumption_id", type = IdType.AUTO)
    private Long consumptionId;
    @TableField("message_id")
    private String messageId;
    @TableField("task_id")
    private Long taskId;
    @TableField("run_id")
    private String runId;
    private Integer attempt;
    @TableField("worker_id")
    private String workerId;
    private String status;
    private String outcome;
    @TableField("error_code")
    private String errorCode;
    @TableField("claim_owner")
    private String claimOwner;
    @TableField("claim_until")
    private LocalDateTime claimUntil;
    @TableField("claim_token")
    private String claimToken;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @TableField("terminal_at")
    private LocalDateTime terminalAt;
}
