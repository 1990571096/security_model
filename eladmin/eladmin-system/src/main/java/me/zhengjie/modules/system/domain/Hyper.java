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
 * 超参数模板管理
 * @author ZJING
 * @date 2025-08-02
 */
@Getter
@Setter
@TableName("sys_hyper")
public class Hyper extends BaseEntity implements Serializable {

    @NotNull(groups = Update.class)
    @TableId(value = "template_id", type = IdType.AUTO)
    @ApiModelProperty(value = "模板ID", hidden = true)
    private Long templateId;

    @NotBlank
    @TableField("template_name")
    @ApiModelProperty(value = "模板名称")
    private String templateName;

    @TableField("description")
    @ApiModelProperty(value = "模板描述")
    private String description;

    @NotNull
    @TableField("enabled")
    @ApiModelProperty(value = "是否启用")
    private Boolean enabled;

    @NotNull
    @TableField("algorithm_id")
    @ApiModelProperty(value = "关联算法ID")
    private Long algorithmId;

    @TableField("parameter_config")
    @ApiModelProperty(value = "参数配置(JSON格式)")
    private String parameterConfig;

    @TableField("parameter_schema")
    @ApiModelProperty(value = "参数模式定义(JSON格式)")
    private String parameterSchema;

    @NotNull
    @TableField("is_default")
    @ApiModelProperty(value = "是否为默认模板")
    private Boolean isDefault;

    @TableField("applicable_datasets")
    @ApiModelProperty(value = "适用数据集类型(JSON数组)")
    private String applicableDatasets;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Hyper hyper = (Hyper) o;
        return Objects.equals(templateId, hyper.templateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId);
    }
}