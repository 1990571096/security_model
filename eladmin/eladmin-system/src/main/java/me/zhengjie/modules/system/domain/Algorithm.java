package me.zhengjie.modules.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;
import me.zhengjie.base.BaseEntity;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;

/**
 * 算法管理
 * @author ZJING
 * @date 2025-07-30
 */
@Getter
@Setter
@TableName("sys_algorithm")
public class Algorithm extends BaseEntity implements Serializable {

    @NotNull(groups = Update.class)
    @TableId(value = "algorithm_id", type = IdType.AUTO)
    @ApiModelProperty(value = "算法ID", hidden = true)
    private Long algorithmId;

    @NotBlank
    @TableField("algorithm_name")
    @ApiModelProperty(value = "算法名称")
    private String algorithmName;

    @TableField("description")
    @ApiModelProperty(value = "算法描述")
    private String description;

    @NotNull
    @TableField("enabled")
    @ApiModelProperty(value = "是否启用")
    private Boolean enabled;

    @NotBlank
    @TableField("algorithm_type")
    @ApiModelProperty(value = "算法类型(data_cleaning,backdoor_detection,model_purification等)")
    private String algorithmType;

    @NotBlank
    @TableField("defense_stage")
    @ApiModelProperty(value = "防御阶段(pre,during,post)")
    private String defenseStage;

    @TableField("algorithm_category")
    @ApiModelProperty(value = "算法分类(statistical,ml_based,deep_learning等)")
    private String algorithmCategory;

    @TableField("implementation_path")
    @ApiModelProperty(value = "算法实现文件路径")
    private String implementationPath;

    @TableField("default_config")
    @ApiModelProperty(value = "默认配置(JSON格式)")
    private String defaultConfig;

    @TableField("config_schema")
    @ApiModelProperty(value = "配置参数模式(JSON格式)")
    private String configSchema;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Algorithm algorithm = (Algorithm) o;
        return Objects.equals(algorithmId, algorithm.algorithmId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(algorithmId);
    }
}