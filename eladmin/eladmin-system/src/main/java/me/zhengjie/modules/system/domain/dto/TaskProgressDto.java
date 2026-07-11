package me.zhengjie.modules.system.domain.dto;

import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务进度查询DTO
 * @author ZJING
 * @date 2025-08-09
 */
@Data
public class TaskProgressDto implements Serializable {

    @ApiModelProperty(value = "任务ID")
    private Long taskId;

    @ApiModelProperty(value = "任务名称")
    private String taskName;

    @ApiModelProperty(value = "任务描述")
    private String description;

    @ApiModelProperty(value = "任务类型: pre_defense-训练前防御, during_defense-训练中防御, post_defense-训练后防御")
    private String taskType;

    @ApiModelProperty(value = "任务状态: pending-等待中, running-运行中, success-成功, failed-失败")
    private String status;

    @ApiModelProperty(value = "进度百分比")
    private Integer progress;

    @ApiModelProperty(value = "开始时间")
    private LocalDateTime startTime;

    @ApiModelProperty(value = "结束时间")
    private LocalDateTime endTime;

    @ApiModelProperty(value = "创建时间")
    private java.sql.Timestamp createTime;

    @ApiModelProperty(value = "创建人")
    private String createBy;

    // 训练前防御特有字段
    @ApiModelProperty(value = "数据集ID（训练前防御）")
    private Long datasetId;

    @ApiModelProperty(value = "输出数据集ID（训练前防御）")
    private Long outputDatasetId;

//    @ApiModelProperty(value = "清洗后样本数量（训练前防御）")
//    private Integer cleanedSampleCount;
//
//    @ApiModelProperty(value = "移除样本数量（训练前防御）")
//    private Integer removedSampleCount;

    @ApiModelProperty(value = "清洗结果(JSON格式): 包含样本统计、损失分布、质量指标等")
    private String cleaningResult;

    // 训练中防御特有字段
    @ApiModelProperty(value = "输入模型ID（训练中防御）")
    private Long inputModelId;

    @ApiModelProperty(value = "输出模型ID（训练中/后防御）")
    private Long outputModelId;

    // 训练后防御特有字段
    @ApiModelProperty(value = "测试数据集ID（训练后防御）")
    private Long testDatasetId;

    @ApiModelProperty(value = "防御结果（训练后防御）: clean-模型清洁, purified-已净化, infected-发现后门, failed-检测失败")
    private String defenseResult;

    // 公共字段
    @ApiModelProperty(value = "算法ID")
    private Long algorithmId;

    @ApiModelProperty(value = "超参数模板ID")
    private Long hyperId;

    @ApiModelProperty(value = "自定义参数配置")
    private String customParams;

    // 扩展信息用于前端显示
    @ApiModelProperty(value = "算法名称（用于显示）")
    private String algorithmName;

    @ApiModelProperty(value = "数据集名称（用于显示）")
    private String datasetName;

    @ApiModelProperty(value = "模型名称（用于显示）")
    private String modelName;

    @ApiModelProperty(value = "运行时长（毫秒）")
    private Long runningDuration;

    @ApiModelProperty(value = "任务类型显示名称")
    private String taskTypeDisplay;

    /**
     * 获取任务类型的中文显示名称
     */
    public String getTaskTypeDisplay() {
        if (taskType == null) {
            return "";
        }
        switch (taskType) {
            case "pre_defense":
                return "训练前防御";
            case "during_defense":
                return "训练中防御";
            case "post_defense":
                return "训练后防御";
            default:
                return taskType;
        }
    }

    /**
     * 计算运行时长
     */
    public Long getRunningDuration() {
        if (startTime == null) {
            return 0L;
        }
        LocalDateTime endTimeOrNow = endTime != null ? endTime : LocalDateTime.now();
        return java.time.Duration.between(startTime, endTimeOrNow).toMillis();
    }

    /**
     * 获取状态的中文显示名称
     */
    public String getStatusDisplay() {
        if (status == null) {
            return "";
        }
        switch (status) {
            case "pending":
                return "等待中";
            case "running":
                return "运行中";
            case "success":
                return "成功";
            case "failed":
                return "失败";
            default:
                return status;
        }
    }
}