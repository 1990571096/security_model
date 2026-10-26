package com.securitymodel.domain.algorithm;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_algorithm")
public class Algorithm {

    @TableId("algorithm_id")
    private Long algorithmId;

    @TableField("algorithm_name")
    private String algorithmName;

    private String description;
    private Boolean enabled;

    @TableField("algorithm_type")
    private String algorithmType;

    @TableField("defense_stage")
    private String defenseStage;

    @TableField("algorithm_category")
    private String algorithmCategory;

    @TableField("implementation_path")
    private String implementationPath;

    @TableField("default_config")
    private String defaultConfig;

    @TableField("config_schema")
    private String configSchema;

    @TableField("create_by")
    private String createBy;

    @TableField("update_by")
    private String updateBy;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
