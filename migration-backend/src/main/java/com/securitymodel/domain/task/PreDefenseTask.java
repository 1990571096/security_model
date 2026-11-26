package com.securitymodel.domain.task;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_pre_defense")
public class PreDefenseTask {

    @TableId("task_id")
    private Long taskId;
    @TableField("task_name")
    private String taskName;
    private String description;
    private String status;
    @TableField("dataset_id")
    private Long datasetId;
    @TableField("algorithm_id")
    private Long algorithmId;
    @TableField("hyper_id")
    private Long hyperId;
    @TableField("custom_params")
    private String customParams;
    @TableField("start_time")
    private LocalDateTime startTime;
    @TableField("end_time")
    private LocalDateTime endTime;
    private Integer progress;
    @TableField("output_dataset_id")
    private Long outputDatasetId;
    @TableField("cleaning_result")
    private String cleaningResult;
    @TableField("execution_snapshot")
    private String executionSnapshot;
    @TableField("current_run_id")
    private String currentRunId;
    @TableField("current_attempt")
    private Integer currentAttempt;
    /** Stable owner ({@code sys_user.user_id}). The only authorization key; {@code createBy} is display only. */
    @TableField("owner_user_id")
    private Long ownerUserId;
    /** {@code user} (created by the platform), {@code legacy} (historical real run), {@code demo}. */
    @TableField("data_origin")
    private String dataOrigin;
    /** {@code matched} / {@code unattributed}; written by the ownership backfill only. */
    @TableField("owner_backfill_status")
    private String ownerBackfillStatus;
    @TableField("create_by")
    private String createBy;
    @TableField("update_by")
    private String updateBy;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;
}
