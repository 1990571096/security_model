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
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 模型表
 * @author ZJING
 * @date 2025-07-27
 */
@Getter
@Setter
@TableName("sys_model")
public class Model extends BaseEntity implements Serializable {

    @NotNull(groups = Update.class)
    @TableId(value = "model_id", type = IdType.AUTO)
    @ApiModelProperty(value = "模型ID", hidden = true)
    private Long modelId;

    @NotBlank
    @TableField("model_name")
    @ApiModelProperty(value = "模型名称")
    private String modelName;

    @TableField("description")
    @ApiModelProperty(value = "模型描述")
    private String description;

    @NotBlank
    @TableField("model_source")
    @ApiModelProperty(value = "模型来源: builtin-内置模型, custom-用户自定义")
    private String modelSource;

    @NotBlank
    @TableField("model_type")
    @ApiModelProperty(value = "模型类型(cnn,rnn,transformer,linear等)")
    private String modelType;

    @TableField("model_category")
    @ApiModelProperty(value = "模型类别(image_classification,text_classification,regression等)")
    private String modelCategory;

    @TableField("architecture")
    @ApiModelProperty(value = "模型架构定义")
    private String architecture;

    @TableField("input_shape")
    @ApiModelProperty(value = "输入形状")
    private String inputShape;

    @TableField("output_shape")
    @ApiModelProperty(value = "输出形状")
    private String outputShape;

    @TableField("parameters_count")
    @ApiModelProperty(value = "参数数量")
    private Long parametersCount;

    @TableField("python_file_path")
    @ApiModelProperty(value = "Python文件路径（仅自定义模型）")
    private String pythonFilePath;

    @TableField("python_file_name")
    @ApiModelProperty(value = "Python文件原名（仅自定义模型）")
    private String pythonFileName;

    @TableField("python_file_size")
    @ApiModelProperty(value = "Python文件大小（仅自定义模型）")
    private Long pythonFileSize;

    @TableField("model_file_path")
    @ApiModelProperty(value = "模型文件路径")
    private String modelFilePath;

    @TableField("model_config")
    @ApiModelProperty(value = "模型配置信息")
    private String modelConfig;

    @TableField("parse_status")
    @ApiModelProperty(value = "解析状态: pending-等待解析, parsing-解析中, success-解析成功, failed-解析失败")
    private String parseStatus = "pending";

    @TableField("parse_message")
    @ApiModelProperty(value = "解析结果消息")
    private String parseMessage;

    @TableField("parse_time")
    @ApiModelProperty(value = "解析完成时间")
    private LocalDateTime parseTime;

    @NotBlank
    @TableField("status")
    @ApiModelProperty(value = "模型状态: draft-草稿, available-可用, maintenance-维护中, deprecated-已弃用")
    private String status = "draft";

//    @NotNull
//    @TableField("created_by")
//    @ApiModelProperty(value = "创建人ID")
//    private Long createdBy;

    @TableField("is_deleted")
    @ApiModelProperty(value = "是否删除")
    private Boolean isDeleted = false;

    // 添加updateBy字段，存储更新人ID（Long类型）
    @TableField("update_by")
    @ApiModelProperty(value = "更新人ID")
    private Long updateBy;

    // 重写BaseEntity的updateBy方法，使其与我们的Long类型字段兼容
    @Override
    public String getUpdateBy() {
        return this.updateBy != null ? this.updateBy.toString() : null;
    }

    @Override
    public void setUpdateBy(String updateBy) {
        // BaseEntity的setter是String类型，我们转换为Long
        try {
            this.updateBy = updateBy != null ? Long.valueOf(updateBy) : null;
        } catch (NumberFormatException e) {
            this.updateBy = null;
        }
    }

    // 添加Long类型的setter用于业务代码调用
    public void setUpdateByLong(Long updateBy) {
        this.updateBy = updateBy;
    }

    public Long getUpdateByLong() {
        return this.updateBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Model sysModel = (Model) o;
        return Objects.equals(modelId, sysModel.modelId) &&
                Objects.equals(modelName, sysModel.modelName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelId, modelName);
    }
}