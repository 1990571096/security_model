package com.securitymodel.domain.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_request_idempotency")
public class RequestIdempotencyRecord {

    public static final String PROCESSING = "processing";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    @TableId(value = "record_id", type = IdType.AUTO)
    private Long recordId;
    private Long ownerUserId;
    private String operation;
    private String idempotencyKey;
    private String requestHash;
    private Long resourceId;
    private String status;
    private Integer responseCode;
    private String responseBody;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}
