package com.securitymodel.domain.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable outbox row. The row and the task/intent are written in one MySQL transaction;
 * RabbitMQ is notified only after commit by a relay worker. {@code messageId} never changes
 * across publish retries, so consumers can de-duplicate by it.
 */
@Data
@TableName("sys_dispatch_outbox")
public class DispatchOutbox {

    public static final String NEW = "new";
    public static final String PUBLISHING = "publishing";
    public static final String PUBLISHED = "published";
    public static final String FAILED = "failed";
    public static final String DEAD = "dead";

    @TableId(value = "outbox_id", type = IdType.AUTO)
    private Long outboxId;
    @TableField("message_id")
    private String messageId;
    @TableField("event_type")
    private String eventType;
    @TableField("task_id")
    private Long taskId;
    @TableField("run_id")
    private String runId;
    private Integer attempt;
    @TableField("intent_id")
    private Long intentId;
    @TableField("payload_hash")
    private String payloadHash;
    @TableField("payload_json")
    private String payloadJson;
    private String status;
    @TableField("publish_attempts")
    private Integer publishAttempts;
    @TableField("claim_owner")
    private String claimOwner;
    @TableField("claim_until")
    private LocalDateTime claimUntil;
    @TableField("claim_token")
    private String claimToken;
    @TableField("next_at")
    private LocalDateTime nextAt;
    @TableField("last_error")
    private String lastError;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @TableField("published_at")
    private LocalDateTime publishedAt;
}
