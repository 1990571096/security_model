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
 * 训练后防御任务
 * @author ZJING
 * @date 2025-08-08
 */
@Getter
@Setter
@TableName("sys_post_defense")
public class PostDefense extends BaseEntity implements Serializable {

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
    @TableField("input_model_id")
    @ApiModelProperty(value = "输入模型ID（待检测/净化的模型）")
    private Long inputModelId;

    @TableField("test_dataset_id")
    @ApiModelProperty(value = "测试数据集ID（可选，用于性能评估）")
    private Long testDatasetId;

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
    @ApiModelProperty(value = "开始时间")
    private LocalDateTime startTime;

    @TableField("end_time")
    @ApiModelProperty(value = "结束时间")
    private LocalDateTime endTime;

    @TableField("progress")
    @ApiModelProperty(value = "进度百分比")
    private Integer progress = 0;

    @TableField("defense_result")
    @ApiModelProperty(value = "防御结果: clean-模型清洁, purified-已净化, infected-发现后门, failed-检测失败")
    private String defenseResult;

    @TableField("output_model_id")
    @ApiModelProperty(value = "输出模型ID（净化后的安全模型）")
    private Long outputModelId;

    @TableField("output_model_path")
    @ApiModelProperty(value = "净化后模型文件路径（兼容性保留）")
    private String outputModelPath;

    @TableField("performance_metrics")
    @ApiModelProperty(value = "性能指标对比(JSON格式)")
    private String performanceMetrics;

    @TableField("risk_assessment")
    @ApiModelProperty(value = "风险评估报告(JSON格式)")
    private String riskAssessment;

    @TableField("detection_report")
    @ApiModelProperty(value = "检测报告(JSON格式)")
    private String detectionReport;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PostDefense that = (PostDefense) o;
        return Objects.equals(taskId, that.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId);
    }
}