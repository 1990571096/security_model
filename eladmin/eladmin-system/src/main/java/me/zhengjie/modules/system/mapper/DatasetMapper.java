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
package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.dto.DatasetQueryCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据集管理 Mapper
 * @author YourName
 * @date 2025-07-30
 */
@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {

    @Select("select dataset_id as datasetId from sys_dataset where dataset_name = #{datasetName}")
    Dataset findByDatasetName(@Param("datasetName") String datasetName);

    /**
     * 查询数据集总数
     * @param criteria 查询条件
     * @return 总数
     */
    Long countAll(@Param("criteria") DatasetQueryCriteria criteria);

    /**
     * 查询所有数据集
     * @param criteria 查询条件
     * @return 数据集列表
     */
    List<Dataset> findAll(@Param("criteria") DatasetQueryCriteria criteria);

    /**
     * 分页查询数据集
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<Dataset> findAll(@Param("criteria") DatasetQueryCriteria criteria, Page<Object> page);

    /**
     * 根据数据集类型查询
     * @param datasetType 数据集类型
     * @return 数据集列表
     */
    List<Dataset> findByDatasetType(@Param("datasetType") String datasetType);

    /**
     * 根据启用状态查询
     * @param enabled 启用状态
     * @return 数据集列表
     */
    List<Dataset> findByEnabled(@Param("enabled") Boolean enabled);

    /**
     * 根据解析状态查询
     * @param parseStatus 解析状态
     * @return 数据集列表
     */
    List<Dataset> findByParseStatus(@Param("parseStatus") String parseStatus);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 数据集列表
     */
    List<Dataset> findByCreateBy(@Param("createBy") String createBy);

    /**
     * 更新解析状态
     * @param datasetId 数据集ID
     * @param parseStatus 解析状态
     * @param parseMessage 解析消息
     */
    void updateParseStatus(@Param("datasetId") Long datasetId, 
                          @Param("parseStatus") String parseStatus, 
                          @Param("parseMessage") String parseMessage);


    /**
     * 根据文件路径查询数据集
     * @param filePath 文件路径
     * @return 数据集信息
     */
    Dataset findByFilePath(@Param("filePath") String filePath);

    /**
     * 批量更新解析状态
     * @param datasetIds 数据集ID列表
     * @param parseStatus 解析状态
     */
    void batchUpdateParseStatus(@Param("datasetIds") List<Long> datasetIds, @Param("parseStatus") String parseStatus);


    /**
     * 更新解析结果
     * @param datasetId 数据集ID
     * @param sampleCount 样本数量
     * @param featureCount 特征数量
     * @param classCount 类别数量
     */
    void updateParseResult(@Param("datasetId") Long datasetId,
                          @Param("sampleCount") Integer sampleCount,
                          @Param("featureCount") Integer featureCount,
                          @Param("classCount") Integer classCount);

    /**
     * 获取可用数据集（启用且解析成功）
     * @param datasetType 数据集类型（可选）
     * @return 可用数据集列表
     */
    List<Dataset> findAvailableDatasets(@Param("datasetType") String datasetType);

    /**
     * 根据文件大小范围查询
     * @param minSize 最小文件大小
     * @param maxSize 最大文件大小
     * @return 数据集列表
     */
    List<Dataset> findByFileSizeRange(@Param("minSize") Long minSize, @Param("maxSize") Long maxSize);

    /**
     * 根据样本数量范围查询
     * @param minCount 最小样本数
     * @param maxCount 最大样本数
     * @return 数据集列表
     */
    List<Dataset> findBySampleCountRange(@Param("minCount") Integer minCount, @Param("maxCount") Integer maxCount);
}