package me.zhengjie.modules.system.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.List;

/**
 * 算法查询条件
 * @author ZJING
 * @date 2025-07-30
 */
@Data
public class AlgorithmQueryCriteria implements Serializable {

    @ApiModelProperty(value = "算法ID")
    private Long algorithmId;

    @ApiModelProperty(value = "模糊查询(算法名称)")
    private String blurry;

    @ApiModelProperty(value = "算法类型")
    private String algorithmType;

    @ApiModelProperty(value = "防御阶段")
    private String defenseStage;

    @ApiModelProperty(value = "算法分类")
    private String algorithmCategory;

    @ApiModelProperty(value = "是否启用")
    private Boolean enabled;

    @ApiModelProperty(value = "创建时间")
    private List<Timestamp> createTime;

    @ApiModelProperty(value = "页码", example = "1")
    private Integer page = 1;

    @ApiModelProperty(value = "每页数据量", example = "10")
    private Integer size = 10;

    @ApiModelProperty(value = "偏移量", hidden = true)
    private long offset;
}