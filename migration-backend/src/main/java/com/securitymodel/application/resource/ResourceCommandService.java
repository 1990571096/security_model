package com.securitymodel.application.resource;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.dataset.VabCifarDataset;
import com.securitymodel.domain.dataset.VabGtsrbDataset;
import com.securitymodel.domain.dataset.VabTinyImageNetDataset;
import com.securitymodel.infrastructure.cache.ResourceCatalogCache;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

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
        currentUser.requireAdmin();
        Dataset dataset = id == null ? new Dataset() : requiredDataset(id);
        String previousPath = dataset.getFilePath();
        String previousType = dataset.getDatasetType();
        dataset.setDatasetName(input.datasetName().trim());
        dataset.setDatasetType(input.datasetType().trim());
        dataset.setFilePath(input.filePath().trim());
        dataset.setDescription(input.description());
        dataset.setEnabled(input.enabled());
        // Registration alone does not establish that a dataset can be parsed by LDD.
        try {
            Path.of(input.filePath());
            if (id == null || !dataset.getFilePath().equals(previousPath)
                    || !dataset.getDatasetType().equals(previousType)) {
                dataset.setParseStatus("pending");
                dataset.setParseMessage("待文件格式与内容验证");
                dataset.setParseTime(null);
                clearStatistics(dataset);
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
        currentUser.requireAdmin();
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
        currentUser.requireAdmin();
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
        currentUser.requireAdmin();
        Algorithm algorithm = requiredAlgorithm(id);
        algorithm.setEnabled(false);
        algorithm.setUpdateBy(currentUser.username());
        algorithm.setUpdateTime(LocalDateTime.now());
        algorithms.updateById(algorithm);
        cache.evict(ResourceCatalogCache.ALGORITHM_LIST, "security-model:resource:algorithm:" + id);
        audit.recordResource("algorithm", id, "DISABLE", "disabled", null);
        return algorithm;
    }

    /** Verifies known CIFAR batches before storing their published dataset statistics. */
    @Transactional
    public Dataset verifyDataset(Long id) {
        currentUser.requireAdmin();
        Dataset dataset = requiredDataset(id);
        clearStatistics(dataset);
        Path path;
        try {
            path = Path.of(dataset.getFilePath()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            return markVerification(dataset, "failed", "数据集路径格式无效");
        }
        if (!Files.exists(path)) {
            return markVerification(dataset, "failed", "路径不存在：" + path);
        }
        if (!Files.isReadable(path)) {
            return markVerification(dataset, "failed", "路径不可读：" + path);
        }
        try {
            VabCifarDataset cifar = VabCifarDataset.fromDirectory(path);
            if (cifar != null) {
                var unusable = cifar.unusableFiles(path);
                if (!unusable.isEmpty()) {
                    return markVerification(dataset, "failed", "CIFAR 批次文件缺失、为空或不可读：" + String.join(", ", unusable));
                }
                var statistics = cifar.verifyOfficialBatches(path);
                dataset.setSampleCount(statistics.trainingSamples());
                dataset.setClassCount(statistics.classes());
                dataset.setFeatureCount(statistics.features());
                dataset.setFileSize(statistics.fileSize());
                return markVerification(dataset, "success", "CIFAR 官方批次校验通过：训练集 %d，测试集 %d，类别 %d"
                        .formatted(statistics.trainingSamples(), statistics.testSamples(), statistics.classes()));
            }
            VabGtsrbDataset gtsrb = VabGtsrbDataset.fromPath(path);
            if (gtsrb != null) {
                if (!gtsrb.hasHdf5Signature()) {
                    return markVerification(dataset, "failed", "GTSRB 文件不是有效的 HDF5：" + gtsrb.file());
                }
                var statistics = gtsrb.statistics();
                dataset.setSampleCount(statistics.trainingSamples());
                dataset.setClassCount(statistics.classes());
                dataset.setFeatureCount(statistics.features());
                dataset.setFileSize(statistics.fileSize());
                return markVerification(dataset, "success",
                        "GTSRB HDF5 校验通过：训练集 %d，测试集 %d，类别 %d，文件 %s"
                                .formatted(statistics.trainingSamples(), statistics.testSamples(),
                                        statistics.classes(), gtsrb.file().getFileName()));
            }
            VabTinyImageNetDataset tinyImageNet = VabTinyImageNetDataset.fromDirectory(path);
            if (tinyImageNet != null) {
                var missing = tinyImageNet.missingLayoutEntries();
                if (!missing.isEmpty()) {
                    return markVerification(dataset, "failed",
                            "Tiny ImageNet 目录缺少必要条目：" + String.join(", ", missing));
                }
                var statistics = tinyImageNet.statistics();
                dataset.setSampleCount(statistics.trainingSamples());
                dataset.setClassCount(statistics.classes());
                dataset.setFeatureCount(statistics.features());
                return markVerification(dataset, "success",
                        "Tiny ImageNet 目录校验通过：训练集 %d，验证集 %d，类别 %d；当前 LDD 暂不支持该数据集"
                                .formatted(statistics.trainingSamples(), statistics.validationSamples(),
                                        statistics.classes()));
            }
            if (Files.isRegularFile(path) && Files.size(path) == 0) {
                return markVerification(dataset, "failed", "数据集文件为空：" + path);
            }
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    if (entries.findAny().isEmpty()) {
                        return markVerification(dataset, "failed", "数据集目录为空：" + path);
                    }
                }
            }
        } catch (IOException exception) {
            return markVerification(dataset, "failed", "路径读取失败：" + exception.getMessage());
        }
        return markVerification(dataset, "success", "路径验证通过：存在且可读");
    }

    private void clearStatistics(Dataset dataset) {
        dataset.setSampleCount(null);
        dataset.setClassCount(null);
        dataset.setFeatureCount(null);
        dataset.setFileSize(null);
    }

    private Dataset markVerification(Dataset dataset, String status, String message) {
        dataset.setParseStatus(status);
        dataset.setParseMessage(message);
        dataset.setParseTime(LocalDateTime.now());
        dataset.setUpdateBy(currentUser.username());
        dataset.setUpdateTime(LocalDateTime.now());
        datasets.updateById(dataset);
        cache.evict(ResourceCatalogCache.DATASET_LIST, "security-model:resource:dataset:" + dataset.getDatasetId());
        audit.recordResource("dataset", dataset.getDatasetId(), "VERIFY", status,
                Map.of("filePath", dataset.getFilePath(), "message", message));
        return dataset;
    }

    private Dataset requiredDataset(Long id) {
        Dataset dataset = datasets.lockForUpdate(id);
        if (dataset == null) throw new ResourceNotFoundException("数据集不存在");
        return dataset;
    }

    private Algorithm requiredAlgorithm(Long id) {
        Algorithm algorithm = algorithms.selectById(id);
        if (algorithm == null) throw new ResourceNotFoundException("算法不存在");
        return algorithm;
    }
}
