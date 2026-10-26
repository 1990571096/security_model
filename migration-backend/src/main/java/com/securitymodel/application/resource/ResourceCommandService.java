package com.securitymodel.application.resource;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.infrastructure.cache.ResourceCatalogCache;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Service
public class ResourceCommandService {
    private final DatasetMapper datasets;
    private final AlgorithmMapper algorithms;
    private final CurrentUserService currentUser;
    private final OperationAuditService audit;
    private final ResourceCatalogCache cache;

    public ResourceCommandService(DatasetMapper datasets, AlgorithmMapper algorithms, CurrentUserService currentUser,
                                  OperationAuditService audit, ResourceCatalogCache cache) {
        this.datasets = datasets;
        this.algorithms = algorithms;
        this.currentUser = currentUser;
        this.audit = audit;
        this.cache = cache;
    }

    public record DatasetInput(
            @NotBlank @Size(max = 100) String datasetName,
            @NotBlank @Size(max = 50) String datasetType,
            @NotBlank @Size(max = 500) String filePath,
            @Min(1) Integer sampleCount,
            @Min(1) Integer classCount,
            @Size(max = 5000) String description,
            boolean enabled) {}

    public record AlgorithmInput(
            @NotBlank @Size(max = 100) String algorithmName,
            @NotBlank @Size(max = 50) String algorithmType,
            @NotBlank @Pattern(regexp = "pre|during|post") String defenseStage,
            @Size(max = 50) String algorithmCategory,
            @Size(max = 500) String implementationPath,
            @Size(max = 5000) String description,
            boolean enabled) {}

    @Transactional
    public Dataset saveDataset(Long id, DatasetInput input) {
        Dataset dataset = id == null ? new Dataset() : requiredDataset(id);
        String previousPath = dataset.getFilePath();
        dataset.setDatasetName(input.datasetName().trim());
        dataset.setDatasetType(input.datasetType().trim());
        dataset.setFilePath(input.filePath().trim());
        dataset.setSampleCount(input.sampleCount());
        dataset.setClassCount(input.classCount());
        dataset.setDescription(input.description());
        dataset.setEnabled(input.enabled());
        // Registration alone does not establish that a dataset can be parsed by LDD.
        try {
            Path.of(input.filePath());
            if (id == null || !input.filePath().trim().equals(previousPath)) {
                dataset.setParseStatus("pending");
                dataset.setParseMessage("待文件格式与内容验证");
                dataset.setParseTime(null);
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("数据集路径格式无效");
        }
        dataset.setUpdateBy(currentUser.username());
        dataset.setUpdateTime(LocalDateTime.now());
        try {
            if (id == null) {
                dataset.setCreateBy(dataset.getUpdateBy());
                dataset.setCreateTime(dataset.getUpdateTime());
                datasets.insert(dataset);
            } else {
                datasets.updateById(dataset);
            }
        } catch (DuplicateKeyException exception) {
            throw new ResourceConflictException("数据集名称已存在", exception);
        }
        cache.evict(ResourceCatalogCache.DATASET_LIST, "security-model:resource:dataset:" + dataset.getDatasetId());
        audit.recordResource("dataset", dataset.getDatasetId(), id == null ? "CREATE" : "UPDATE",
                dataset.getParseStatus(), input);
        return dataset;
    }

    @Transactional
    public Algorithm saveAlgorithm(Long id, AlgorithmInput input) {
        Algorithm algorithm = id == null ? new Algorithm() : requiredAlgorithm(id);
        algorithm.setAlgorithmName(input.algorithmName().trim());
        algorithm.setAlgorithmType(input.algorithmType().trim());
        algorithm.setDefenseStage(input.defenseStage());
        algorithm.setAlgorithmCategory(input.algorithmCategory());
        algorithm.setDescription(input.description());
        algorithm.setImplementationPath(input.implementationPath());
        algorithm.setEnabled(input.enabled());
        algorithm.setUpdateBy(currentUser.username());
        algorithm.setUpdateTime(LocalDateTime.now());
        try {
            if (id == null) {
                algorithm.setCreateBy(algorithm.getUpdateBy());
                algorithm.setCreateTime(algorithm.getUpdateTime());
                algorithms.insert(algorithm);
            } else {
                algorithms.updateById(algorithm);
            }
        } catch (DuplicateKeyException exception) {
            throw new ResourceConflictException("算法名称已存在", exception);
        }
        cache.evict(ResourceCatalogCache.ALGORITHM_LIST, "security-model:resource:algorithm:" + algorithm.getAlgorithmId());
        audit.recordResource("algorithm", algorithm.getAlgorithmId(), id == null ? "CREATE" : "UPDATE",
                algorithm.getEnabled() ? "enabled" : "disabled", input);
        return algorithm;
    }

    @Transactional
    public Dataset disableDataset(Long id) {
        Dataset dataset = requiredDataset(id);
        dataset.setEnabled(false);
        dataset.setUpdateBy(currentUser.username());
        dataset.setUpdateTime(LocalDateTime.now());
        datasets.updateById(dataset);
        cache.evict(ResourceCatalogCache.DATASET_LIST, "security-model:resource:dataset:" + id);
        audit.recordResource("dataset", id, "DISABLE", "disabled", null);
        return dataset;
    }

    @Transactional
    public Algorithm disableAlgorithm(Long id) {
        Algorithm algorithm = requiredAlgorithm(id);
        algorithm.setEnabled(false);
        algorithm.setUpdateBy(currentUser.username());
        algorithm.setUpdateTime(LocalDateTime.now());
        algorithms.updateById(algorithm);
        cache.evict(ResourceCatalogCache.ALGORITHM_LIST, "security-model:resource:algorithm:" + id);
        audit.recordResource("algorithm", id, "DISABLE", "disabled", null);
        return algorithm;
    }

    private Dataset requiredDataset(Long id) {
        Dataset dataset = datasets.selectById(id);
        if (dataset == null) throw new ResourceNotFoundException("数据集不存在");
        return dataset;
    }

    private Algorithm requiredAlgorithm(Long id) {
        Algorithm algorithm = algorithms.selectById(id);
        if (algorithm == null) throw new ResourceNotFoundException("算法不存在");
        return algorithm;
    }
}
