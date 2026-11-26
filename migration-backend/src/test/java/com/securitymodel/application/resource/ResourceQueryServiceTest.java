package com.securitymodel.application.resource;

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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(cache.getListOrLoad(eq(ResourceCatalogCache.DATASET_LIST), eq(Dataset.class), any()))
                .thenReturn(Optional.of(List.of(dataset)));

        assertEquals(List.of(dataset), service.findDatasets());

        verifyNoInteractions(datasets);
    }

    @Test
    void cacheMissRunsTheDatabaseLoaderForDatasetCatalog() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(9L);
        when(datasets.selectList(any())).thenReturn(List.of(dataset));
        when(cache.getListOrLoad(eq(ResourceCatalogCache.DATASET_LIST), eq(Dataset.class), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(2, Supplier.class).get()));

        assertEquals(List.of(dataset), service.findDatasets());

        verify(datasets).selectList(any());
    }

    @Test
    void cacheHitAvoidsDatabaseReadForAlgorithmById() {
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmId(11L);
        when(cache.getOrLoad(eq("security-model:resource:algorithm:11"), eq(Algorithm.class), any()))
                .thenReturn(Optional.of(algorithm));

        assertEquals(algorithm, service.findAlgorithm(11L));

        verifyNoInteractions(algorithms);
    }

    @Test
    void redisFailureIsHandledByCacheGatewayAndQueryCanFallback() {
        Hyper hyper = new Hyper();
        hyper.setTemplateId(12L);
        when(hypers.selectList(any())).thenReturn(List.of(hyper));
        when(cache.getListOrLoad(eq(ResourceCatalogCache.HYPER_LIST), eq(Hyper.class), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(2, Supplier.class).get()));

        assertEquals(List.of(hyper), service.findAvailableHyper());

        verify(hypers).selectList(any());
    }
}
