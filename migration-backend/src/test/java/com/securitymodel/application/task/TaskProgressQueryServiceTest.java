package com.securitymodel.application.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskProgressQueryServiceTest {
    private final PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
    private final ResourceQueryService resources = mock(ResourceQueryService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final TaskAccessGuard guard = mock(TaskAccessGuard.class);
    private final TaskProgressQueryService service =
            new TaskProgressQueryService(mapper, resources, currentUser, guard);

    @Test
    void batchesResourceNamesAcrossThePage() {
        Page<PreDefenseTask> result = new Page<>(1, 20);
        result.setRecords(List.of(task(1), task(2), task(3)));
        result.setTotal(31);
        when(mapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(result);
        when(currentUser.userId()).thenReturn(7L);
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

    @Test
    void statisticsAreScopedToTheCaller() {
        when(currentUser.userId()).thenReturn(7L);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(2L);

        Map<String, Object> stats = service.statistics();

        assertEquals("current-user", stats.get("scope"));
        // One total plus five per-status counts, all through the scoped wrapper.
        verify(mapper, times(6)).selectCount(any(Wrapper.class));
    }

    @Test
    void successWithResultCountOnlyCountsCompletedTasksWithAStoredResult() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(3L);
        when(currentUser.userId()).thenReturn(7L);

        assertEquals(3L, service.successWithResultCount());
        verify(mapper, times(1)).selectCount(any(Wrapper.class));
    }

    @Test
    void trendGroupsScopedTasksByDayAndRejectsUnboundedWindows() {
        PreDefenseTask success = task(1L);
        success.setCreateTime(LocalDate.now().atTime(10, 0));
        success.setStatus("success");
        PreDefenseTask failed = task(2L);
        failed.setCreateTime(LocalDate.now().atTime(11, 0));
        failed.setStatus("failed");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(success, failed));
        when(currentUser.userId()).thenReturn(7L);

        var trend = service.trend(3);

        assertEquals(3, trend.size());
        assertEquals(LocalDate.now().minusDays(2).toString(), trend.get(0).get("date"));
        assertEquals(2L, trend.get(2).get("total"));
        assertEquals(1L, trend.get(2).get("success"));
        assertEquals(1L, trend.get(2).get("failed"));
        assertEquals(0L, trend.get(2).get("pending"));
        verify(mapper, times(1)).selectList(any(Wrapper.class));
        assertThrows(IllegalArgumentException.class, () -> service.trend(0));
        assertThrows(IllegalArgumentException.class, () -> service.trend(31));
    }

    @Test
    void anotherUsersTaskIsRejectedInsteadOfReturned() {
        PreDefenseTask foreign = task(99L);
        foreign.setOwnerUserId(8L);
        when(mapper.selectById(99L)).thenReturn(foreign);
        when(guard.canRead(foreign)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.findTask(99L));
    }

    @Test
    void missingTaskReturnsNull() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertNull(service.findTask(404L));
    }

    private PreDefenseTask task(long id) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(id);
        task.setAlgorithmId(7L);
        task.setDatasetId(8L);
        return task;
    }
}
