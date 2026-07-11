/*
 *  Copyright 2019-2025 Zheng Jie
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package me.zhengjie.modules.system.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.List;

/**
 * 模型查询条件
 * @author YourName
 * @date 2025-07-27
 */
@Data
public class ModelQueryCriteria implements Serializable {

    @ApiModelProperty(value = "模型ID")
    private Long modelId;

    @ApiModelProperty(value = "模糊查询(模型名称)")
    private String blurry;

    @ApiModelProperty(value = "模型来源")
    private String modelSource;

    @ApiModelProperty(value = "模型类型")
    private String modelType;

    @ApiModelProperty(value = "模型类别")
    private String modelCategory;

    @ApiModelProperty(value = "解析状态")
    private String parseStatus;

    @ApiModelProperty(value = "模型状态")
    private String status;

//    @ApiModelProperty(value = "创建人名字")
//    private Long createBy;

    @ApiModelProperty(value = "创建时间")
    private List<Timestamp> createTime;

    @ApiModelProperty(value = "页码", example = "1")
    private Integer page = 1;

    @ApiModelProperty(value = "每页数据量", example = "10")
    private Integer size = 10;

    @ApiModelProperty(value = "偏移量", hidden = true)
    private long offset;
}