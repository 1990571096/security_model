package com.securitymodel.application.resource;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.hyper.Hyper;
import com.securitymodel.infrastructure.cache.ResourceCatalogCache;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import com.securitymodel.infrastructure.persistence.mapper.HyperMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ResourceQueryService {

    private final DatasetMapper datasetMapper;
    private final AlgorithmMapper algorithmMapper;
    private final HyperMapper hyperMapper;
    private final ResourceCatalogCache cache;

    public ResourceQueryService(
            DatasetMapper datasetMapper,
            AlgorithmMapper algorithmMapper,
            HyperMapper hyperMapper,
            ResourceCatalogCache cache) {
        this.datasetMapper = datasetMapper;
        this.algorithmMapper = algorithmMapper;
        this.hyperMapper = hyperMapper;
        this.cache = cache;
    }

    public List<Dataset> findAvailableDatasets(String datasetType) {
        var query = Wrappers.<Dataset>lambdaQuery()
                .eq(Dataset::getEnabled, true)
                .eq(Dataset::getParseStatus, "success")
                .orderByDesc(Dataset::getDatasetId);
        if (datasetType != null && !datasetType.isBlank()) {
            query.eq(Dataset::getDatasetType, datasetType);
        }
        return datasetMapper.selectList(query);
    }

    public Dataset findDataset(Long datasetId) {
        return cache.get("security-model:resource:dataset:" + datasetId, Dataset.class)
                .orElseGet(() -> {
                    Dataset dataset = datasetMapper.selectById(datasetId);
                    if (dataset != null) {
                        cache.put("security-model:resource:dataset:" + datasetId, dataset);
                    }
                    return dataset;
                });
    }

    public List<Dataset> findDatasets() {
        return cache.getList(ResourceCatalogCache.DATASET_LIST, Dataset.class)
                .orElseGet(() -> {
                    List<Dataset> datasets = datasetMapper.selectList(
                            Wrappers.<Dataset>lambdaQuery().orderByDesc(Dataset::getDatasetId));
                    cache.put(ResourceCatalogCache.DATASET_LIST, datasets);
                    return datasets;
                });
    }

    public Map<Long, Dataset> findDatasetsByIds(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : datasetMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Dataset::getDatasetId, Function.identity()));
    }

    public List<Algorithm> findAvailableAlgorithms(String defenseStage, String algorithmType) {
        var query = Wrappers.<Algorithm>lambdaQuery()
                .eq(Algorithm::getEnabled, true)
                .orderByDesc(Algorithm::getAlgorithmId);
        if (defenseStage != null && !defenseStage.isBlank()) {
            query.eq(Algorithm::getDefenseStage, defenseStage);
        }
        if (algorithmType != null && !algorithmType.isBlank()) {
            query.eq(Algorithm::getAlgorithmType, algorithmType);
        }
        return algorithmMapper.selectList(query);
    }

    public Algorithm findAlgorithm(Long algorithmId) {
        return cache.get("security-model:resource:algorithm:" + algorithmId, Algorithm.class)
                .orElseGet(() -> {
                    Algorithm algorithm = algorithmMapper.selectById(algorithmId);
                    if (algorithm != null) {
                        cache.put("security-model:resource:algorithm:" + algorithmId, algorithm);
                    }
                    return algorithm;
                });
    }

    public List<Algorithm> findAlgorithms() {
        return cache.getList(ResourceCatalogCache.ALGORITHM_LIST, Algorithm.class)
                .orElseGet(() -> {
                    List<Algorithm> algorithms = algorithmMapper.selectList(
                            Wrappers.<Algorithm>lambdaQuery().orderByDesc(Algorithm::getAlgorithmId));
                    cache.put(ResourceCatalogCache.ALGORITHM_LIST, algorithms);
                    return algorithms;
                });
    }

    public Map<Long, Algorithm> findAlgorithmsByIds(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : algorithmMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Algorithm::getAlgorithmId, Function.identity()));
    }

    public List<Hyper> findAvailableHyper() {
        return cache.getList(ResourceCatalogCache.HYPER_LIST, Hyper.class)
                .orElseGet(() -> {
                    List<Hyper> hypers = hyperMapper.selectList(Wrappers.<Hyper>lambdaQuery()
                            .eq(Hyper::getEnabled, true)
                            .orderByDesc(Hyper::getTemplateId));
                    cache.put(ResourceCatalogCache.HYPER_LIST, hypers);
                    return hypers;
                });
    }

    public Hyper findHyper(Long templateId) {
        return hyperMapper.selectById(templateId);
    }

    public List<Hyper> findHyperByAlgorithm(Long algorithmId) {
        return hyperMapper.selectList(Wrappers.<Hyper>lambdaQuery()
                .eq(Hyper::getAlgorithmId, algorithmId)
                .eq(Hyper::getEnabled, true)
                .orderByDesc(Hyper::getIsDefault)
                .orderByDesc(Hyper::getTemplateId));
    }

    public Hyper findDefaultHyper(Long algorithmId) {
        return hyperMapper.selectOne(Wrappers.<Hyper>lambdaQuery()
                .eq(Hyper::getAlgorithmId, algorithmId)
                .eq(Hyper::getEnabled, true)
                .eq(Hyper::getIsDefault, true)
                .last("limit 1"));
    }
}
