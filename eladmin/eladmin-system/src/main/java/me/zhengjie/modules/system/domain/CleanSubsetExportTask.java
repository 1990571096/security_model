package me.zhengjie.modules.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;
import me.zhengjie.base.BaseEntity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 清洗后数据子集导出任务。
 *
 * <p>导出是独立于 LDD 清洗主任务的异步工作单元，避免大文件打包占用下载请求线程，
 * 同时保留导出状态、失败原因和产物路径，便于重试和答辩演示。</p>
 */
@Getter
@Setter
@TableName("sys_pre_defense_export")
public class CleanSubsetExportTask extends BaseEntity implements Serializable {

    @TableId(value = "export_id", type = IdType.AUTO)
    @ApiModelProperty(value = "导出任务ID", readOnly = true)
    private Long exportId;

    @TableField("task_id")
    @ApiModelProperty(value = "训练前防御任务ID")
    private Long taskId;

    @TableField("isolation_ratio")
    @ApiModelProperty(value = "隔离比例")
    private Integer isolationRatio;

    @TableField("status")
    @ApiModelProperty(value = "导出状态: pending, running, success, failed")
    private String status = "pending";

    @TableField("progress")
    @ApiModelProperty(value = "导出进度百分比")
    private Integer progress = 0;

    @TableField("file_path")
    @ApiModelProperty(value = "ZIP 文件路径", readOnly = true)
    private String filePath;

    @TableField("metadata_path")
    @ApiModelProperty(value = "导出元数据路径", readOnly = true)
    private String metadataPath;

    @TableField("error_code")
    @ApiModelProperty(value = "错误码", readOnly = true)
    private String errorCode;

    @TableField("failure_reason")
    @ApiModelProperty(value = "失败原因", readOnly = true)
    private String failureReason;

    @TableField("start_time")
    @ApiModelProperty(value = "开始时间", readOnly = true)
    private LocalDateTime startTime;

    @TableField("end_time")
    @ApiModelProperty(value = "结束时间", readOnly = true)
    private LocalDateTime endTime;
}
