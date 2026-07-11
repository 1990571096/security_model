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
 * 数据集管理
 * @author ZJING
 * @date 2025-07-30
 */
@Getter
@Setter
@TableName("sys_dataset")
public class Dataset extends BaseEntity implements Serializable {

    @NotNull(groups = Update.class)
    @TableId(value = "dataset_id", type = IdType.AUTO)
    @ApiModelProperty(value = "数据集ID", hidden = true)
    private Long datasetId;

    @NotBlank
    @TableField("dataset_name")
    @ApiModelProperty(value = "数据集名称")
    private String datasetName;

    @TableField("description")
    @ApiModelProperty(value = "数据集描述")
    private String description;

    @NotNull
    @TableField("enabled")
    @ApiModelProperty(value = "是否启用")
    private Boolean enabled;

    @NotBlank
    @TableField("dataset_type")
    @ApiModelProperty(value = "数据集类型(image,text,tabular等)")
    private String datasetType;

    @TableField("file_path")
    @ApiModelProperty(value = "文件存储路径")
    private String filePath;

    @TableField("file_name")
    @ApiModelProperty(value = "原始文件名")
    private String fileName;

    @TableField("file_size")
    @ApiModelProperty(value = "文件大小(字节)")
    private Long fileSize;

    @TableField("sample_count")
    @ApiModelProperty(value = "样本数量")
    private Integer sampleCount;

    @TableField("feature_count")
    @ApiModelProperty(value = "特征数量")
    private Integer featureCount;

    @TableField("class_count")
    @ApiModelProperty(value = "类别数量")
    private Integer classCount;

    @TableField("parse_status")
    @ApiModelProperty(value = "解析状态: pending-等待解析, parsing-解析中, success-解析成功, failed-解析失败")
    private String parseStatus = "pending";

    @TableField("parse_message")
    @ApiModelProperty(value = "解析结果消息")
    private String parseMessage;

    @TableField("parse_time")
    @ApiModelProperty(value = "解析完成时间")
    private LocalDateTime parseTime;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Dataset dataset = (Dataset) o;
        return Objects.equals(datasetId, dataset.datasetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasetId);
    }
}