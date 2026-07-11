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
 * 训练中防御任务
 * @author ZJING
 * @date 2025-08-04
 */
@Getter
@Setter
@TableName("sys_during_defense")
public class DuringDefense extends BaseEntity implements Serializable {

    @NotNull(groups = Update.class)
    @TableId(value = "task_id", type = IdType.AUTO)
    @ApiModelProperty(value = "任务ID", hidden = true)
    private Long taskId;

    @NotBlank
    @TableField("task_name")
    @ApiModelProperty(value = "任务名称")
    private String taskName;

    @TableField("description")
    @ApiModelProperty(value = "任务描述")
    private String description;

    @NotBlank
    @TableField("status")
    @ApiModelProperty(value = "任务状态: pending-等待中, running-运行中, success-成功, failed-失败")
    private String status = "pending";

    @NotNull
    @TableField("dataset_id")
    @ApiModelProperty(value = "训练数据集ID")
    private Long datasetId;

    @NotNull
    @TableField("input_model_id")
    @ApiModelProperty(value = "输入模型ID")
    private Long inputModelId;

    @NotNull
    @TableField("algorithm_id")
    @ApiModelProperty(value = "防御算法ID")
    private Long algorithmId;

    @TableField("hyper_id")
    @ApiModelProperty(value = "超参数模板ID")
    private Long hyperId;

    @TableField("custom_params")
    @ApiModelProperty(value = "自定义参数配置(JSON格式)")
    private String customParams;

    @TableField("start_time")
    @ApiModelProperty(value = "训练开始时间")
    private LocalDateTime startTime;

    @TableField("end_time")
    @ApiModelProperty(value = "训练结束时间")
    private LocalDateTime endTime;

    @TableField("progress")
    @ApiModelProperty(value = "训练进度百分比")
    private Integer progress = 0;

    @TableField("output_model_id")
    @ApiModelProperty(value = "输出模型ID")
    private Long outputModelId;

    @TableField("training_log")
    @ApiModelProperty(value = "训练日志(JSON格式)")
    private String trainingLog;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DuringDefense that = (DuringDefense) o;
        return Objects.equals(taskId, that.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId);
    }
}