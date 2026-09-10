package me.zhengjie.modules.system.rest;

import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.service.TaskProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
class TaskProgressControllerTest {

    @Mock
    private TaskProgressService taskProgressService;

    private TaskProgressController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskProgressController(taskProgressService);
    }

    @Test
    void acceptsValidBatchProgressRequest() {
        List<Map<String, Object>> taskInfos = Collections.singletonList(taskInfo(12L));
        Map<String, Object> progress = Collections.singletonMap("pre_defense_12", 50);
        when(taskProgressService.getBatchTaskProgress(taskInfos)).thenReturn(progress);

        ResponseEntity<Map<String, Object>> response =
                controller.getBatchTaskProgress(taskInfos);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(progress, response.getBody());
        verify(taskProgressService).getBatchTaskProgress(taskInfos);
    }

    @Test
    void rejectsEmptyBatchProgressRequest() {
        assertThrows(
                BadRequestException.class,
                () -> controller.getBatchTaskProgress(Collections.emptyList()));

        verifyNoInteractions(taskProgressService);
    }

    @Test
    void rejectsMalformedBatchProgressItem() {
        Map<String, Object> item = new HashMap<>();
        item.put("taskType", "pre_defense");
        item.put("taskId", "not-a-number");

        assertThrows(
                BadRequestException.class,
                () -> controller.getBatchTaskProgress(Collections.singletonList(item)));

        verifyNoInteractions(taskProgressService);
    }

    @Test
    void rejectsUnsupportedBatchProgressTaskType() {
        Map<String, Object> item = new HashMap<>();
        item.put("taskType", "during_defense");
        item.put("taskId", 12L);

        assertThrows(
                BadRequestException.class,
                () -> controller.getBatchTaskProgress(Collections.singletonList(item)));

        verifyNoInteractions(taskProgressService);
    }

    @Test
    void rejectsOversizedBatchProgressRequest() {
        List<Map<String, Object>> taskInfos = new ArrayList<>();
        for (long taskId = 1; taskId <= 101; taskId++) {
            taskInfos.add(taskInfo(taskId));
        }

        assertThrows(
                BadRequestException.class,
                () -> controller.getBatchTaskProgress(taskInfos));

        verifyNoInteractions(taskProgressService);
    }

    private Map<String, Object> taskInfo(Long taskId) {
        Map<String, Object> item = new HashMap<>();
        item.put("taskType", "pre_defense");
        item.put("taskId", taskId);
        return item;
    }
}
