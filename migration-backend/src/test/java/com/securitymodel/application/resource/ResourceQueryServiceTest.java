package com.securitymodel.application.resource;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.hyper.Hyper;
import com.securitymodel.infrastructure.cache.ResourceCatalogCache;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import com.securitymodel.infrastructure.persistence.mapper.HyperMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResourceQueryServiceTest {
    private final DatasetMapper datasets = mock(DatasetMapper.class);
    private final AlgorithmMapper algorithms = mock(AlgorithmMapper.class);
    private final HyperMapper hypers = mock(HyperMapper.class);
    private final ResourceCatalogCache cache = mock(ResourceCatalogCache.class);
    private final ResourceQueryService service = new ResourceQueryService(datasets, algorithms, hypers, cache);

    @Test
    void cacheHitAvoidsDatabaseReadForDatasetCatalog() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(8L);
        when(cache.getList(ResourceCatalogCache.DATASET_LIST, Dataset.class))
                .thenReturn(Optional.of(List.of(dataset)));

        assertEquals(List.of(dataset), service.findDatasets());

        verifyNoInteractions(datasets);
        verify(cache, never()).put(any(), any());
    }

    @Test
    void cacheMissReadsDatabaseAndWarmsDatasetCatalog() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(9L);
        when(cache.getList(ResourceCatalogCache.DATASET_LIST, Dataset.class))
                .thenReturn(Optional.empty());
        when(datasets.selectList(any())).thenReturn(List.of(dataset));

        assertEquals(List.of(dataset), service.findDatasets());

        verify(datasets).selectList(any());
        verify(cache).put(ResourceCatalogCache.DATASET_LIST, List.of(dataset));
    }

    @Test
    void cacheHitAvoidsDatabaseReadForAlgorithmById() {
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmId(11L);
        when(cache.get("security-model:resource:algorithm:11", Algorithm.class))
                .thenReturn(Optional.of(algorithm));

        assertEquals(algorithm, service.findAlgorithm(11L));

        verifyNoInteractions(algorithms);
    }

    @Test
    void redisFailureIsHandledByCacheGatewayAndQueryCanFallback() {
        when(cache.getList(ResourceCatalogCache.HYPER_LIST, Hyper.class))
                .thenReturn(Optional.empty());
        Hyper hyper = new Hyper();
        hyper.setTemplateId(12L);
        when(hypers.selectList(any())).thenReturn(List.of(hyper));

        assertEquals(List.of(hyper), service.findAvailableHyper());

        verify(hypers).selectList(any());
        verify(cache).put(ResourceCatalogCache.HYPER_LIST, List.of(hyper));
    }
}
