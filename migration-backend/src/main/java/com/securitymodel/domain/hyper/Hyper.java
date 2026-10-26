package com.securitymodel.domain.hyper;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_hyper")
public class Hyper {

    @TableId("template_id")
    private Long templateId;

    @TableField("template_name")
    private String templateName;

    private String description;
    private Boolean enabled;

    @TableField("algorithm_id")
    private Long algorithmId;

    @TableField("parameter_config")
    private String parameterConfig;

    @TableField("parameter_schema")
    private String parameterSchema;

    @TableField("is_default")
    private Boolean isDefault;

    @TableField("applicable_datasets")
    private String applicableDatasets;

    @TableField("create_by")
    private String createBy;

    @TableField("update_by")
    private String updateBy;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
