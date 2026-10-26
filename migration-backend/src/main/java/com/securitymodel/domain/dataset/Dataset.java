package com.securitymodel.domain.dataset;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Read model for the existing sys_dataset table.
 *
 * The migration deliberately maps the legacy columns instead of introducing
 * a second resource schema while both applications share the database.
 */
@Data
@TableName("sys_dataset")
public class Dataset {

    @TableId("dataset_id")
    private Long datasetId;

    @TableField("dataset_name")
    private String datasetName;

    private String description;
    private Boolean enabled;

    @TableField("dataset_type")
    private String datasetType;

    @TableField("file_path")
    private String filePath;

    @TableField("file_name")
    private String fileName;

    @TableField("file_size")
    private Long fileSize;

    @TableField("sample_count")
    private Integer sampleCount;

    @TableField("feature_count")
    private Integer featureCount;

    @TableField("class_count")
    private Integer classCount;

    @TableField("parse_status")
    private String parseStatus;

    @TableField("parse_message")
    private String parseMessage;

    @TableField("parse_time")
    private LocalDateTime parseTime;

    @TableField("create_by")
    private String createBy;

    @TableField("update_by")
    private String updateBy;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
