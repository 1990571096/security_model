package com.securitymodel.domain.assistant;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Per-user daily request quota row. */
@Data
@TableName("sys_assistant_usage_daily")
public class AssistantUsageDaily {
    private Long userId;
    private LocalDate usageDate;
    private Integer requestCount;
    private LocalDateTime updatedAt;
}
