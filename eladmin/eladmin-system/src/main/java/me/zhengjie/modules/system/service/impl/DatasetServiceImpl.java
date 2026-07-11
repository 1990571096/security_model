package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.exception.EntityExistException;
import me.zhengjie.modules.system.domain.Dataset;
import me.zhengjie.modules.system.domain.dto.DatasetQueryCriteria;
import me.zhengjie.modules.system.mapper.DatasetMapper;
import me.zhengjie.modules.system.service.DatasetService;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据集管理服务实现
 * @author ZJING
 * @date 2025-07-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetServiceImpl extends ServiceImpl<DatasetMapper, Dataset> implements DatasetService {

    private final DatasetMapper datasetMapper;

    @Override
    public Dataset findById(Long datasetId) {
        Dataset dataset = datasetMapper.selectById(datasetId);
        if (ObjectUtil.isNull(dataset)) {
            throw new BadRequestException("数据集不存在");
        }
        return dataset;
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void create(Dataset resources) {
//        // 检查数据集名称是否已存在
//        if (datasetMapper.findByDatasetName(resources.getDatasetName()) != null) {
//            throw new EntityExistException(Dataset.class, "datasetName", resources.getDatasetName());
//        }
//
//        // 验证数据集参数
//        validateDataset(resources);
//
//        // 设置默认值
//        if (ObjectUtil.isEmpty(resources.getParseStatus())) {
//            resources.setParseStatus("pending");
//        }
//        if (ObjectUtil.isNull(resources.getEnabled())) {
//            resources.setEnabled(true);
//        }
//
//        datasetMapper.insert(resources);
//        log.info("用户 {} 创建了新数据集: {} (ID: {})",
//                SecurityUtils.getCurrentUsername(),
//                resources.getDatasetName(),
//                resources.getDatasetId());
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Dataset resources) {
        Dataset dataset = findById(resources.getDatasetId());

        // 检查数据集名称是否已被其他数据集使用
        Dataset datasetByName = datasetMapper.findByDatasetName(resources.getDatasetName());
        if (datasetByName != null && !datasetByName.getDatasetId().equals(resources.getDatasetId())) {
            throw new EntityExistException(Dataset.class, "datasetName", resources.getDatasetName());
        }

        // 验证数据集参数
        validateDataset(resources);

        datasetMapper.updateById(resources);
        log.info("用户 {} 更新了数据集: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getDatasetName(),
                resources.getDatasetId());
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void delete(Set<Long> datasetIds) {
//        String currentUser = SecurityUtils.getCurrentUsername();
//
//        for (Long datasetId : datasetIds) {
//            Dataset dataset = findById(datasetId);
//
//            // 权限检查：只能删除自己创建的数据集
//            if (!ObjectUtil.equal(dataset.getCreateBy(), currentUser)) {
//                throw new BadRequestException("只能删除自己创建的数据集【" + dataset.getDatasetName() + "】");
//            }
//
//            // 业务检查：正在解析的数据集不能删除
//            if ("parsing".equals(dataset.getParseStatus())) {
//                throw new BadRequestException("数据集【" + dataset.getDatasetName() + "】正在解析中，无法删除");
//            }
//
//            // TODO: 检查数据集是否被任务使用
//            // if (isDatasetInUse(datasetId)) {
//            //     throw new BadRequestException("数据集【" + dataset.getDatasetName() + "】正在被任务使用，无法删除");
//            // }
//
//            // 清理数据集文件
//            cleanupDatasetFiles(dataset);
//        }
//
//        datasetMapper.deleteBatchIds(datasetIds);
//        log.info("用户 {} 删除了 {} 个数据集", currentUser, datasetIds.size());
//    }

    /**
     * 清理数据集相关文件
     */
    private void cleanupDatasetFiles(Dataset dataset) {
        try {
            if (ObjectUtil.isNotEmpty(dataset.getFilePath())) {
                File datasetFile = new File(dataset.getFilePath());
                if (datasetFile.exists() && !datasetFile.delete()) {
                    log.warn("删除数据集文件失败: {}", dataset.getFilePath());
                }
            }
        } catch (Exception e) {
            log.warn("删除数据集文件时发生异常: {}", e.getMessage());
        }
    }

    @Override
    public Dataset findByDatasetName(String datasetName) {
        return datasetMapper.findByDatasetName(datasetName);
    }

    @Override
    public PageResult<Dataset> queryAll(DatasetQueryCriteria criteria, Page<Object> page) {
        criteria.setOffset(page.offset());
        IPage<Dataset> datasetPage = datasetMapper.findAll(criteria, page);
        return PageUtil.toPage(datasetPage);
    }

    @Override
    public List<Dataset> queryAll(DatasetQueryCriteria criteria) {
        return datasetMapper.findAll(criteria);
    }

    @Override
    public void download(List<Dataset> datasets, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Dataset dataset : datasets) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("数据集ID", dataset.getDatasetId());
            map.put("数据集名称", dataset.getDatasetName());
            map.put("数据集描述", dataset.getDescription());
            map.put("数据集类型", dataset.getDatasetType());
            map.put("启用状态", dataset.getEnabled() ? "启用" : "禁用");
            map.put("文件名", dataset.getFileName());
            map.put("文件大小(MB)", dataset.getFileSize() != null ? dataset.getFileSize() / (1024 * 1024) : null);
            map.put("样本数量", dataset.getSampleCount());
            map.put("特征数量", dataset.getFeatureCount());
            map.put("类别数量", dataset.getClassCount());
            map.put("解析状态", dataset.getParseStatus());
            map.put("解析时间", dataset.getParseTime());
            map.put("创建时间", dataset.getCreateTime());
            list.add(map);
        }
        FileUtil.downloadExcel(list, response);
    }

    @Override
    public List<Dataset> findByDatasetType(String datasetType) {
        return datasetMapper.findByDatasetType(datasetType);
    }

    @Override
    public List<Dataset> findByEnabled(Boolean enabled) {
        return datasetMapper.findByEnabled(enabled);
    }

    @Override
    public List<Dataset> findByParseStatus(String parseStatus) {
        return datasetMapper.findByParseStatus(parseStatus);
    }

    @Override
    public List<Dataset> findAvailableDatasets(String datasetType) {
        return datasetMapper.findAvailableDatasets(datasetType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateParseStatus(Long datasetId, String parseStatus, String parseMessage) {
        datasetMapper.updateParseStatus(datasetId, parseStatus, parseMessage);
        log.info("更新数据集 {} 解析状态为: {}", datasetId, parseStatus);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateParseResult(Long datasetId, Integer sampleCount, Integer featureCount, Integer classCount) {
        datasetMapper.updateParseResult(datasetId, sampleCount, featureCount, classCount);
        log.info("更新数据集 {} 解析结果: 样本={}, 特征={}, 类别={}", 
                datasetId, sampleCount, featureCount, classCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startParsing(Long datasetId) {
        Dataset dataset = findById(datasetId);
        
        if (!"pending".equals(dataset.getParseStatus())) {
            throw new BadRequestException("只有等待解析的数据集才能启动解析");
        }
        
        // 更新解析状态为解析中
        updateParseStatus(datasetId, "parsing", "开始解析数据集...");
        
        // TODO: 这里应该调用实际的数据集解析逻辑
        // 可能需要异步执行，或者调用外部服务
        
        log.info("启动数据集解析: {} (ID: {})", dataset.getDatasetName(), datasetId);
    }

    @Override
    public boolean isDatasetAvailable(Long datasetId) {
        try {
            Dataset dataset = findById(datasetId);
            return dataset.getEnabled() && "success".equals(dataset.getParseStatus());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<Dataset> findByFileSizeRange(Long minSize, Long maxSize) {
        return datasetMapper.findByFileSizeRange(minSize, maxSize);
    }

    @Override
    public List<Dataset> findBySampleCountRange(Integer minCount, Integer maxCount) {
        return datasetMapper.findBySampleCountRange(minCount, maxCount);
    }

    @Override
    public Map<String, Object> getDatasetStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // 按类型统计
        stats.put("imageCount", datasetMapper.findByDatasetType("image").size());
        stats.put("textCount", datasetMapper.findByDatasetType("text").size());
        stats.put("tabularCount", datasetMapper.findByDatasetType("tabular").size());
        
        // 按状态统计
        stats.put("pendingCount", datasetMapper.findByParseStatus("pending").size());
        stats.put("parsingCount", datasetMapper.findByParseStatus("parsing").size());
        stats.put("successCount", datasetMapper.findByParseStatus("success").size());
        stats.put("failedCount", datasetMapper.findByParseStatus("failed").size());
        
        // 启用状态统计
        stats.put("enabledCount", datasetMapper.findByEnabled(true).size());
        stats.put("disabledCount", datasetMapper.findByEnabled(false).size());
        
        // 总数统计
        DatasetQueryCriteria criteria = new DatasetQueryCriteria();
        stats.put("totalCount", datasetMapper.findAll(criteria).size());
        
        return stats;
    }

    @Override
    public Dataset findByFilePath(String filePath) {
        return datasetMapper.findByFilePath(filePath);
    }

    @Override
    public boolean isDatasetNameDuplicate(String datasetName, Long excludeId) {
        Dataset existingDataset = datasetMapper.findByDatasetName(datasetName);
        if (existingDataset == null) {
            return false;
        }
        return excludeId == null || !existingDataset.getDatasetId().equals(excludeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateParseStatus(List<Long> datasetIds, String parseStatus) {
        if (datasetIds != null && !datasetIds.isEmpty()) {
            datasetMapper.batchUpdateParseStatus(datasetIds, parseStatus);
            log.info("批量更新 {} 个数据集解析状态为: {}", datasetIds.size(), parseStatus);
        }
    }

    @Override
    public void cleanupDatasetFile(Dataset dataset) {
        try {
            if (ObjectUtil.isNotEmpty(dataset.getFilePath())) {
                File datasetFile = new File(dataset.getFilePath());
                if (datasetFile.exists()) {
                    if (datasetFile.delete()) {
                        log.info("成功删除数据集文件: {}", dataset.getFilePath());
                    } else {
                        log.warn("删除数据集文件失败: {}", dataset.getFilePath());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("删除数据集文件时发生异常: {}", e.getMessage());
        }
    }

    // 修改create方法，添加文件路径重复检查
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(Dataset resources) {
        // 检查数据集名称是否已存在
        if (isDatasetNameDuplicate(resources.getDatasetName(), null)) {
            throw new EntityExistException(Dataset.class, "datasetName", resources.getDatasetName());
        }

        // 检查文件路径是否重复（如果有文件路径）
        if (ObjectUtil.isNotEmpty(resources.getFilePath())) {
            Dataset existingByPath = findByFilePath(resources.getFilePath());
            if (existingByPath != null) {
                throw new BadRequestException("文件路径已被其他数据集使用");
            }
        }

        // 验证数据集参数
        validateDataset(resources);

        // 设置默认值
        if (ObjectUtil.isEmpty(resources.getParseStatus())) {
            resources.setParseStatus("pending");
        }
        if (ObjectUtil.isNull(resources.getEnabled())) {
            resources.setEnabled(true);
        }

        datasetMapper.insert(resources);
        log.info("用户 {} 创建了新数据集: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getDatasetName(),
                resources.getDatasetId());
    }

    // 修改delete方法，增强文件清理
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Set<Long> datasetIds) {
        String currentUser = SecurityUtils.getCurrentUsername();

        for (Long datasetId : datasetIds) {
            Dataset dataset = findById(datasetId);

            // 权限检查：只能删除自己创建的数据集
            if (!ObjectUtil.equal(dataset.getCreateBy(), currentUser)) {
                throw new BadRequestException("只能删除自己创建的数据集【" + dataset.getDatasetName() + "】");
            }

            // 业务检查：正在解析的数据集不能删除
            if ("parsing".equals(dataset.getParseStatus())) {
                throw new BadRequestException("数据集【" + dataset.getDatasetName() + "】正在解析中，无法删除");
            }

            // TODO: 检查数据集是否被任务使用
            // if (isDatasetInUse(datasetId)) {
            //     throw new BadRequestException("数据集【" + dataset.getDatasetName() + "】正在被任务使用，无法删除");
            // }

            // 清理数据集文件
            cleanupDatasetFile(dataset);
        }

        datasetMapper.deleteBatchIds(datasetIds);
        log.info("用户 {} 删除了 {} 个数据集", currentUser, datasetIds.size());
    }

    // 修改validateDataset方法，添加文件验证
    @Override
    public void validateDataset(Dataset resources) {
        // 基础参数验证
        if (ObjectUtil.isEmpty(resources.getDatasetName())) {
            throw new BadRequestException("数据集名称不能为空");
        }
        if (ObjectUtil.isEmpty(resources.getDatasetType())) {
            throw new BadRequestException("数据集类型不能为空");
        }

        // 数据集类型验证
        List<String> validTypes = Arrays.asList("image", "text", "tabular");
        if (!validTypes.contains(resources.getDatasetType())) {
            throw new BadRequestException("数据集类型必须是: " + String.join(", ", validTypes));
        }

        // 文件验证
        if (ObjectUtil.isNotEmpty(resources.getFilePath())) {
            File file = new File(resources.getFilePath());
            if (!file.exists()) {
                throw new BadRequestException("指定的数据集文件不存在");
            }

            // 验证文件大小与数据库记录是否一致
            if (resources.getFileSize() != null && file.length() != resources.getFileSize()) {
                log.warn("数据集文件大小不一致，文件实际大小: {}, 数据库记录: {}",
                        file.length(), resources.getFileSize());
            }
        }

        // 文件大小验证
        if (resources.getFileSize() != null && resources.getFileSize() > 500 * 1024 * 1024) {
            throw new BadRequestException("数据集文件大小不能超过 500MB");
        }
    }

    @Override
    public List<Dataset> findAllAvailable(String datasetType) {
        return datasetMapper.findAvailableDatasets(datasetType);
    }

    @Override
    public List<Dataset> findByCreateBy(String createBy) {
        return datasetMapper.findByCreateBy(createBy);
    }
}