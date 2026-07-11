package me.zhengjie.modules.system.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.List;

/**
 * 训练后防御任务查询条件
 * @author ZJING
 * @date 2025-08-08
 */
@Data
public class PostDefenseQueryCriteria implements Serializable {

    @ApiModelProperty(value = "任务ID")
    private Long taskId;

    @ApiModelProperty(value = "模糊查询(任务名称)")
    private String blurry;

    @ApiModelProperty(value = "任务状态")
    private String status;

    @ApiModelProperty(value = "输入模型ID")
    private Long inputModelId;

    @ApiModelProperty(value = "测试数据集ID")
    private Long testDatasetId;

    @ApiModelProperty(value = "算法ID")
    private Long algorithmId;

    @ApiModelProperty(value = "防御结果")
    private String defenseResult;

    @ApiModelProperty(value = "创建时间")
    private List<Timestamp> createTime;

    @ApiModelProperty(value = "页码", example = "1")
    private Integer page = 1;

    @ApiModelProperty(value = "每页数据量", example = "10")
    private Integer size = 10;

    @ApiModelProperty(value = "偏移量", hidden = true)
    private long offset;
}