package com.securitymodel.domain.assistant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One server-issued, hashed, single-use write confirmation. */
@Data
@TableName("sys_assistant_confirmation")
public class AssistantConfirmation {

    public static final String PENDING = "pending";
    public static final String CONSUMING = "consuming";
    public static final String CONSUMED = "consumed";
    public static final String EXPIRED = "expired";

    @TableId(value = "confirmation_id", type = IdType.AUTO)
    private Long confirmationId;
    private String tokenHash;
    private Long userId;
    private String operation;
    private Long targetTaskId;
    private String targetRunId;
    private String contextHash;
    private String requestJson;
    private String previewJson;
    private String status;
    private Integer resultCode;
    private String resultJson;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
}
