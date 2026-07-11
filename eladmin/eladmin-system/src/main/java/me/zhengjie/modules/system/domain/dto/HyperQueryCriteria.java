package me.zhengjie.modules.system.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.List;

/**
 * 超参数模板查询条件
 * @author ZJING
 * @date 2025-08-02
 */
@Data
public class HyperQueryCriteria implements Serializable {

    @ApiModelProperty(value = "模板ID")
    private Long templateId;

    @ApiModelProperty(value = "模糊查询(模板名称)")
    private String blurry;

    @ApiModelProperty(value = "关联算法ID")
    private Long algorithmId;

    @ApiModelProperty(value = "是否启用")
    private Boolean enabled;

    @ApiModelProperty(value = "是否为默认模板")
    private Boolean isDefault;

    @ApiModelProperty(value = "适用数据集类型")
    private String datasetType;

    @ApiModelProperty(value = "创建时间")
    private List<Timestamp> createTime;

    @ApiModelProperty(value = "页码", example = "1")
    private Integer page = 1;

    @ApiModelProperty(value = "每页数据量", example = "10")
    private Integer size = 10;

    @ApiModelProperty(value = "偏移量", hidden = true)
    private long offset;
}