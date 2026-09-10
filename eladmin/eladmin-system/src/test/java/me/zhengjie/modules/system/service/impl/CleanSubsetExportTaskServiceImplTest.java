package me.zhengjie.modules.system.service.impl;

import me.zhengjie.modules.system.domain.CleanSubsetExportTask;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.CleanSubsetExportTaskMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.CleanSubsetExportService;
import me.zhengjie.modules.system.service.executor.AfterCommitTaskDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class CleanSubsetExportTaskServiceImplTest {

    @Mock
    private CleanSubsetExportTaskMapper exportTaskMapper;

    @Mock
    private PreDefenseMapper preDefenseMapper;

    @Mock
    private CleanSubsetExportService cleanSubsetExportService;

    @Mock
    private CleanSubsetExportTaskExecutor exportTaskExecutor;

    private CleanSubsetExportTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CleanSubsetExportTaskServiceImpl(
                exportTaskMapper,
                preDefenseMapper,
                cleanSubsetExportService,
                exportTaskExecutor,
                new AfterCommitTaskDispatcher());
    }

    @Test
    void retriesExistingFailedExportAndKeepsSingleExportRecord() {
        PreDefense sourceTask = sourceTask();
        CleanSubsetExportTask failed = exportTask(7L, "failed");
        CleanSubsetExportTask reset = exportTask(7L, "pending");
        when(cleanSubsetExportService.describe(sourceTask)).thenReturn(description(5));
        when(exportTaskMapper.findByTaskAndRatio(12L, 5)).thenReturn(failed);
        when(exportTaskMapper.resetForRetry(7L)).thenReturn(1);
        when(exportTaskMapper.selectById(7L)).thenReturn(reset);

        CleanSubsetExportTask returned = service.submit(sourceTask, 5);

        assertSame(reset, returned);
        verify(exportTaskMapper).resetForRetry(7L);
        verify(exportTaskExecutor).execute(7L);
        verify(exportTaskMapper, never()).insert(any(CleanSubsetExportTask.class));
    }

    @Test
    void atomicallyCreatesAndSchedulesOnlyTheWinningSubmission() {
        PreDefense sourceTask = sourceTask();
        CleanSubsetExportTask created = exportTask(9L, "pending");
        when(cleanSubsetExportService.describe(sourceTask)).thenReturn(description(5));
        when(exportTaskMapper.findByTaskAndRatio(12L, 5))
                .thenReturn(null)
                .thenReturn(created);
        when(exportTaskMapper.insertIfAbsent(any(CleanSubsetExportTask.class))).thenAnswer(invocation -> {
            CleanSubsetExportTask request = invocation.getArgument(0);
            request.setExportId(9L);
            return 1;
        });

        CleanSubsetExportTask returned = service.submit(sourceTask, 5);

        assertSame(created, returned);
        ArgumentCaptor<CleanSubsetExportTask> captor =
                ArgumentCaptor.forClass(CleanSubsetExportTask.class);
        verify(exportTaskMapper).insertIfAbsent(captor.capture());
        assertEquals("system", captor.getValue().getCreateBy());
        verify(exportTaskExecutor).execute(9L);
    }

    @Test
    void returnsExistingSuccessfulExportWithoutSchedulingDuplicateWork() {
        PreDefense sourceTask = sourceTask();
        CleanSubsetExportTask successful = exportTask(8L, "success");
        when(cleanSubsetExportService.describe(sourceTask)).thenReturn(description(10));
        when(exportTaskMapper.findByTaskAndRatio(12L, 10)).thenReturn(successful);

        CleanSubsetExportTask returned = service.submit(sourceTask, 10);

        assertSame(successful, returned);
        verify(exportTaskExecutor, never()).execute(8L);
        verify(exportTaskMapper, never()).resetForRetry(8L);
    }

    @Test
    void marksPendingExportFailedWhenExecutorRejectsSubmission() {
        PreDefense sourceTask = sourceTask();
        CleanSubsetExportTask created = exportTask(16L, "pending");
        AfterCommitTaskDispatcher afterCommitTaskDispatcher = mock(AfterCommitTaskDispatcher.class);
        service = new CleanSubsetExportTaskServiceImpl(
                exportTaskMapper,
                preDefenseMapper,
                cleanSubsetExportService,
                exportTaskExecutor,
                afterCommitTaskDispatcher);
        when(cleanSubsetExportService.describe(sourceTask)).thenReturn(description(5));
        when(exportTaskMapper.findByTaskAndRatio(12L, 5))
                .thenReturn(null)
                .thenReturn(created);
        when(exportTaskMapper.insertIfAbsent(any(CleanSubsetExportTask.class))).thenAnswer(invocation -> {
            CleanSubsetExportTask request = invocation.getArgument(0);
            request.setExportId(16L);
            return 1;
        });
        when(exportTaskMapper.failBeforeExecution(
                eq(16L), eq("EXPORT_QUEUE_FULL"),
                eq("导出任务执行线程池已满，任务未能提交: queue full"),
                any())).thenReturn(1);
        doAnswer(invocation -> {
            Consumer<RuntimeException> rejectionHandler = invocation.getArgument(1);
            rejectionHandler.accept(new RejectedExecutionException("queue full"));
            return null;
        }).when(afterCommitTaskDispatcher).dispatch(any(Runnable.class), any(Consumer.class));

        CleanSubsetExportTask returned = service.submit(sourceTask, 5);

        assertSame(created, returned);
        verify(exportTaskMapper).failBeforeExecution(
                eq(16L), eq("EXPORT_QUEUE_FULL"),
                eq("导出任务执行线程池已满，任务未能提交: queue full"),
                any());
        verify(exportTaskExecutor, never()).execute(16L);
    }

    @Test
    void concurrentSubmissionsCreateOneRecordAndDispatchOneExport() throws Exception {
        PreDefense sourceTask = sourceTask();
        CleanSubsetExportTask created = exportTask(15L, "pending");
        AtomicBoolean recordCreated = new AtomicBoolean(false);
        CyclicBarrier insertBarrier = new CyclicBarrier(2);
        when(cleanSubsetExportService.describe(sourceTask)).thenReturn(description(5));
        when(exportTaskMapper.findByTaskAndRatio(12L, 5))
                .thenAnswer(invocation -> recordCreated.get() ? created : null);
        when(exportTaskMapper.insertIfAbsent(any(CleanSubsetExportTask.class))).thenAnswer(invocation -> {
            insertBarrier.await(5, TimeUnit.SECONDS);
            return recordCreated.compareAndSet(false, true) ? 1 : 0;
        });

        ExecutorService submissions = Executors.newFixedThreadPool(2);
        try {
            Future<CleanSubsetExportTask> first =
                    submissions.submit(() -> service.submit(sourceTask, 5));
            Future<CleanSubsetExportTask> second =
                    submissions.submit(() -> service.submit(sourceTask, 5));

            assertSame(created, first.get(10, TimeUnit.SECONDS));
            assertSame(created, second.get(10, TimeUnit.SECONDS));
        } finally {
            submissions.shutdownNow();
            submissions.awaitTermination(5, TimeUnit.SECONDS);
        }

        verify(exportTaskMapper, times(2)).insertIfAbsent(any(CleanSubsetExportTask.class));
        verify(exportTaskExecutor, times(1)).execute(15L);
    }

    private PreDefense sourceTask() {
        PreDefense task = new PreDefense();
        task.setTaskId(12L);
        task.setStatus("success");
        task.setCleaningResult("{}");
        return task;
    }

    private CleanSubsetExportTask exportTask(Long exportId, String status) {
        CleanSubsetExportTask task = new CleanSubsetExportTask();
        task.setExportId(exportId);
        task.setTaskId(12L);
        task.setIsolationRatio(5);
        task.setStatus(status);
        return task;
    }

    private Map<String, Object> description(Integer ratio) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("available", true);
        description.put("isolationRatios", Collections.singletonList(ratio));
        return description;
    }
}
