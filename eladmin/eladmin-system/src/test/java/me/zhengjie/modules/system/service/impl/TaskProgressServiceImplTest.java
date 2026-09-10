package me.zhengjie.modules.system.service.impl;

import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.service.AlgorithmService;
import me.zhengjie.modules.system.service.DatasetService;
import me.zhengjie.modules.system.service.ModelService;
import me.zhengjie.modules.system.service.PreDefenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskProgressServiceImplTest {

    @Mock
    private PreDefenseService preDefenseService;

    @Mock
    private AlgorithmService algorithmService;

    @Mock
    private DatasetService datasetService;

    @Mock
    private ModelService modelService;

    @Mock
    private ThreadPoolTaskExecutor taskExecutor;

    private TaskProgressServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaskProgressServiceImpl(
                preDefenseService,
                algorithmService,
                datasetService,
                modelService,
                taskExecutor);
    }

    @Test
    void returnsProgressForValidBatchRequest() {
        List<Map<String, Object>> taskInfos = Collections.singletonList(taskInfo(12L));
        when(preDefenseService.getTaskProgress(12L)).thenReturn(75);

        Map<String, Object> result = service.getBatchTaskProgress(taskInfos);

        assertEquals(75, result.get("pre_defense_12"));
        verify(preDefenseService).getTaskProgress(12L);
    }

    @Test
    void convertsTaskLookupFailureToUnknownProgress() {
        List<Map<String, Object>> taskInfos = Collections.singletonList(taskInfo(12L));
        when(preDefenseService.getTaskProgress(12L))
                .thenThrow(new BadRequestException("任务不存在"));

        Map<String, Object> result = service.getBatchTaskProgress(taskInfos);

        assertEquals(-1, result.get("pre_defense_12"));
    }

    @Test
    void rejectsInvalidTaskInfoBeforeLookup() {
        Map<String, Object> item = taskInfo(12L);
        item.put("taskType", "during_defense");

        assertThrows(
                BadRequestException.class,
                () -> service.getBatchTaskProgress(Collections.singletonList(item)));

        verifyNoInteractions(preDefenseService);
    }

    @Test
    void rejectsOversizedTaskInfoBatch() {
        List<Map<String, Object>> taskInfos = new ArrayList<>();
        for (long taskId = 1; taskId <= 101; taskId++) {
            taskInfos.add(taskInfo(taskId));
        }

        assertThrows(
                BadRequestException.class,
                () -> service.getBatchTaskProgress(taskInfos));

        verifyNoInteractions(preDefenseService);
    }

    private Map<String, Object> taskInfo(Long taskId) {
        Map<String, Object> item = new HashMap<>();
        item.put("taskType", "pre_defense");
        item.put("taskId", taskId);
        return item;
    }
}
