package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.report.PreDefenseLogQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.persistence.mapper.TaskDispatchIntentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskFailureDiagnosisTest {
    private static final long TASK_ID = 2122901116713205761L;
    private final TaskAccessGuard tasks = mock(TaskAccessGuard.class);
    private final TaskDispatchIntentMapper intents = mock(TaskDispatchIntentMapper.class);
    private final TaskFailureDiagnosis service = new TaskFailureDiagnosis(tasks, intents, new ObjectMapper());

    @Test
    void usesCurrentAttemptAndDoesNotEchoRawReport() {
        var task = failure();
        task.setCleaningResult("""
                {"run_id":"current","attempt":2,"error_code":"GPU_OOM","error_message":"private-log-details"}
                """);
        when(tasks.requireOwned(TASK_ID)).thenReturn(task);
        var intent = submittedIntent();
        when(intents.findByRun(TASK_ID, "current", 2)).thenReturn(intent);

        var diagnosis = service.diagnose(TASK_ID, "current", 2);

        assertEquals(Long.toString(TASK_ID), diagnosis.taskId());
        assertEquals("执行阶段", diagnosis.stage());
        assertTrue(diagnosis.answer().contains("GPU_OOM"));
        assertTrue(diagnosis.answer().contains("是否实际启动"));
        assertFalse(diagnosis.answer().contains("private-log-details"));
    }

    @Test
    void ignoresOlderReportAndShowsMissingEvidence() {
        var task = failure();
        task.setCleaningResult("""
                {"run_id":"previous","attempt":1,"error_code":"GPU_OOM"}
                """);
        when(tasks.requireOwned(TASK_ID)).thenReturn(task);

        var diagnosis = service.diagnose(TASK_ID, "current", 2);

        assertEquals("阶段待确认", diagnosis.stage());
        assertTrue(diagnosis.answer().contains("缺少可核对的报告错误码"));
        assertFalse(diagnosis.answer().contains("GPU_OOM"));
    }

    @Test
    void conflictingEvidenceDoesNotDetermineAStage() {
        var task = failure();
        task.setCleaningResult("""
                {"run_id":"current","attempt":2,"error_code":"RESULT_MISSING"}
                """);
        when(tasks.requireOwned(TASK_ID)).thenReturn(task);
        var intent = submittedIntent();
        intent.setErrorCode("RABBIT_PUBLISH_FAILED");
        when(intents.findByRun(TASK_ID, "current", 2)).thenReturn(intent);

        var diagnosis = service.diagnose(TASK_ID, "current", 2);

        assertEquals("阶段待确认", diagnosis.stage());
        assertTrue(diagnosis.answer().contains("错误码不同"));
    }

    @Test
    void genericExitCodeStaysPendingUntilLogShowsEncodingFailure() {
        var task = failure();
        task.setCleaningResult("""
                {"run_id":"current","attempt":2,"error_code":"PYTHON_PROCESS_EXITED"}
                """);
        when(tasks.requireOwned(TASK_ID)).thenReturn(task);
        var logService = mock(PreDefenseLogQueryService.class);
        when(logService.getLogs(TASK_ID, java.util.Map.of())).thenReturn(java.util.Map.of(
                "available", true,
                "logs", java.util.List.of(java.util.Map.of(
                        "content", "UnicodeEncodeError: 'gbk' codec can't encode character"))));
        var service = new TaskFailureDiagnosis(tasks, intents, new ObjectMapper(), logService);

        var diagnosis = service.diagnose(TASK_ID, "current", 2);

        assertEquals("执行阶段（编码故障）", diagnosis.stage());
        assertTrue(diagnosis.answer().contains("编码异常信号"));
        assertFalse(diagnosis.answer().contains("UnicodeEncodeError"));
        assertFalse(diagnosis.answer().contains("gbk"));
    }

    @Test
    void deniesAnotherUsersTaskBeforeReadingEvidence() {
        when(tasks.requireOwned(TASK_ID)).thenThrow(new AccessDeniedException("禁止访问"));

        assertThrows(AccessDeniedException.class, () -> service.diagnose(TASK_ID, "current", 2));
        verifyNoInteractions(intents);
    }

    @Test
    void rejectsDiagnosisWhenTaskWasRetriedDuringEvidenceRead() {
        var retried = failure();
        retried.setStatus("running");
        retried.setCurrentAttempt(3);
        when(tasks.requireOwned(TASK_ID)).thenReturn(failure(), retried);

        var exception = assertThrows(AssistantException.class, () -> service.diagnose(TASK_ID, "current", 2));
        assertEquals("ASSISTANT_DIAGNOSIS_STALE", exception.getCode());
    }

    private PreDefenseTask failure() {
        var task = new PreDefenseTask();
        task.setStatus("failed");
        task.setCurrentRunId("current");
        task.setCurrentAttempt(2);
        return task;
    }

    private TaskDispatchIntent submittedIntent() {
        var intent = new TaskDispatchIntent();
        intent.setStatus(TaskDispatchIntent.SUBMITTED);
        return intent;
    }
}
