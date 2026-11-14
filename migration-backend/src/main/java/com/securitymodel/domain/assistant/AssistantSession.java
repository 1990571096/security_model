package com.securitymodel.domain.assistant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Sanitized structured conversation summary; no raw prompt or tool arguments. */
@Data
@TableName("sys_assistant_session")
public class AssistantSession {

    public static final String ACTIVE = "active";
    public static final String DELETED = "deleted";

    @TableId(value = "session_id", type = IdType.AUTO)
    private Long sessionId;
    private Long userId;
    private String title;
    private String status;
    private String summaryJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}
