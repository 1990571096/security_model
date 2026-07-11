package me.zhengjie.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.dto.DatasetQueryCriteria;
import me.zhengjie.utils.PageResult;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 数据集管理服务接口
 * @author ZJING
 * @date 2025-07-30
 */
public interface DatasetService extends IService<Dataset> {

    /**
     * 根据ID查询数据集
     * @param datasetId 数据集ID
     * @return 数据集信息
     */
    Dataset findById(Long datasetId);

    /**
     * 创建数据集
     * @param resources 数据集信息
     */
    void create(Dataset resources);

    /**
     * 更新数据集
     * @param resources 数据集信息
     */
    void update(Dataset resources);

    /**
     * 删除数据集
     * @param datasetIds 数据集ID集合
     */
    void delete(Set<Long> datasetIds);

    /**
     * 根据数据集名称查询
     * @param datasetName 数据集名称
     * @return 数据集信息
     */
    Dataset findByDatasetName(String datasetName);

    /**
     * 分页查询数据集
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    PageResult<Dataset> queryAll(DatasetQueryCriteria criteria, Page<Object> page);

    /**
     * 查询所有数据集（不分页）
     * @param criteria 查询条件
     * @return 数据集列表
     */
    List<Dataset> queryAll(DatasetQueryCriteria criteria);

    /**
     * 导出数据集数据
     * @param datasets 待导出的数据
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    void download(List<Dataset> datasets, HttpServletResponse response) throws IOException;

    /**
     * 根据数据集类型查询
     * @param datasetType 数据集类型
     * @return 数据集列表
     */
    List<Dataset> findByDatasetType(String datasetType);

    /**
     * 根据启用状态查询
     * @param enabled 启用状态
     * @return 数据集列表
     */
    List<Dataset> findByEnabled(Boolean enabled);

    /**
     * 根据解析状态查询
     * @param parseStatus 解析状态
     * @return 数据集列表
     */
    List<Dataset> findByParseStatus(String parseStatus);

    /**
     * 获取可用数据集（启用且解析成功）
     * @param datasetType 数据集类型（可选）
     * @return 可用数据集列表
     */
    List<Dataset> findAvailableDatasets(String datasetType);

    /**
     * 更新解析状态
     * @param datasetId 数据集ID
     * @param parseStatus 解析状态
     * @param parseMessage 解析消息
     */
    void updateParseStatus(Long datasetId, String parseStatus, String parseMessage);

    /**
     * 更新解析结果
     * @param datasetId 数据集ID
     * @param sampleCount 样本数量
     * @param featureCount 特征数量
     * @param classCount 类别数量
     */
    void updateParseResult(Long datasetId, Integer sampleCount, Integer featureCount, Integer classCount);

    /**
     * 启动数据集解析
     * @param datasetId 数据集ID
     */
    void startParsing(Long datasetId);

    /**
     * 验证数据集是否可用
     * @param datasetId 数据集ID
     * @return 是否可用
     */
    boolean isDatasetAvailable(Long datasetId);

    /**
     * 根据文件大小范围查询
     * @param minSize 最小文件大小
     * @param maxSize 最大文件大小
     * @return 数据集列表
     */
    List<Dataset> findByFileSizeRange(Long minSize, Long maxSize);

    /**
     * 根据样本数量范围查询
     * @param minCount 最小样本数
     * @param maxCount 最大样本数
     * @return 数据集列表
     */
    List<Dataset> findBySampleCountRange(Integer minCount, Integer maxCount);

    /**
     * 获取数据集统计信息
     * @return 统计信息
     */
    java.util.Map<String, Object> getDatasetStatistics();

    /**
     * 验证数据集参数
     * @param resources 数据集信息
     */
    void validateDataset(Dataset resources);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 数据集列表
     */
    List<Dataset> findByCreateBy(String createBy);

    /**
     * 根据文件路径查询数据集
     * @param filePath 文件路径
     * @return 数据集信息
     */
    Dataset findByFilePath(String filePath);

    /**
     * 获取所有可用数据集（不分页）
     * @param datasetType 数据集类型（可选）
     * @return 可用数据集列表
     */
    List<Dataset> findAllAvailable(String datasetType);

    /**
     * 检查数据集名称是否重复
     * @param datasetName 数据集名称
     * @param excludeId 排除的数据集ID（编辑时使用）
     * @return 是否重复
     */
    boolean isDatasetNameDuplicate(String datasetName, Long excludeId);

    /**
     * 批量更新解析状态
     * @param datasetIds 数据集ID列表
     * @param parseStatus 解析状态
     */
    void batchUpdateParseStatus(List<Long> datasetIds, String parseStatus);

    /**
     * 清理数据集文件
     * @param dataset 数据集信息
     */
    void cleanupDatasetFile(Dataset dataset);


}