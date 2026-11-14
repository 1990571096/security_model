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
        return cache.getOrLoad("security-model:resource:dataset:" + datasetId, Dataset.class,
                () -> datasetMapper.selectById(datasetId)).orElse(null);
    }

    public List<Dataset> findDatasets() {
        return cache.getListOrLoad(ResourceCatalogCache.DATASET_LIST, Dataset.class,
                () -> datasetMapper.selectList(
                        Wrappers.<Dataset>lambdaQuery().orderByDesc(Dataset::getDatasetId))).orElse(List.of());
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
        return cache.getOrLoad("security-model:resource:algorithm:" + algorithmId, Algorithm.class,
                () -> algorithmMapper.selectById(algorithmId)).orElse(null);
    }

    public List<Algorithm> findAlgorithms() {
        return cache.getListOrLoad(ResourceCatalogCache.ALGORITHM_LIST, Algorithm.class,
                () -> algorithmMapper.selectList(
                        Wrappers.<Algorithm>lambdaQuery().orderByDesc(Algorithm::getAlgorithmId))).orElse(List.of());
    }

    public Map<Long, Algorithm> findAlgorithmsByIds(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : algorithmMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Algorithm::getAlgorithmId, Function.identity()));
    }

    public List<Hyper> findAvailableHyper() {
        return cache.getListOrLoad(ResourceCatalogCache.HYPER_LIST, Hyper.class,
                () -> hyperMapper.selectList(Wrappers.<Hyper>lambdaQuery()
                        .eq(Hyper::getEnabled, true)
                        .orderByDesc(Hyper::getTemplateId))).orElse(List.of());
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
