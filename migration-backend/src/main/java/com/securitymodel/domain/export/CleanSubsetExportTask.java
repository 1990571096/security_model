package com.securitymodel.domain.export;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_pre_defense_export")
public class CleanSubsetExportTask {

    @TableId("export_id")
    private Long exportId;
    @TableField("task_id")
    private Long taskId;
    @TableField("isolation_ratio")
    private Integer isolationRatio;
    private String status;
    private Integer progress;
    @TableField("file_path")
    private String filePath;
    @TableField("metadata_path")
    private String metadataPath;
    @TableField("error_code")
    private String errorCode;
    @TableField("failure_reason")
    private String failureReason;
    @TableField("start_time")
    private LocalDateTime startTime;
    @TableField("end_time")
    private LocalDateTime endTime;
    @TableField("create_by")
    private String createBy;
    @TableField("update_by")
    private String updateBy;
    @TableField("create_time")
    private LocalDateTime createTime;
    @TableField("update_time")
    private LocalDateTime updateTime;
}
