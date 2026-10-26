package com.securitymodel.application.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskProgressQueryServiceTest {
    private final PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
    private final ResourceQueryService resources = mock(ResourceQueryService.class);
    private final TaskProgressQueryService service = new TaskProgressQueryService(mapper, resources);

    @Test
    void batchesResourceNamesAcrossThePage() {
        Page<PreDefenseTask> result = new Page<>(1, 20);
        result.setRecords(List.of(task(1), task(2), task(3)));
        result.setTotal(31);
        when(mapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(result);
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmName("LDD");
        Dataset dataset = new Dataset();
        dataset.setDatasetName("CIFAR-10");
        when(resources.findAlgorithmsByIds(Set.of(7L))).thenReturn(Map.of(7L, algorithm));
        when(resources.findDatasetsByIds(Set.of(8L))).thenReturn(Map.of(8L, dataset));

        var page = service.findPage("success", null, 1, 20);

        assertEquals(31, page.total());
        assertEquals(3, page.records().size());
        assertEquals("LDD", page.records().get(2).getAlgorithmName());
        verify(resources, times(1)).findAlgorithmsByIds(Set.of(7L));
        verify(resources, times(1)).findDatasetsByIds(Set.of(8L));
        verify(resources, never()).findAlgorithm(any());
        verify(resources, never()).findDataset(any());
    }

    @Test
    void rejectsUnboundedPageSizesBeforeQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.findPage(null, null, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> service.findPage(null, null, 1, 101));
        verifyNoInteractions(mapper);
    }

    private PreDefenseTask task(long id) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(id);
        task.setAlgorithmId(7L);
        task.setDatasetId(8L);
        return task;
    }
}
