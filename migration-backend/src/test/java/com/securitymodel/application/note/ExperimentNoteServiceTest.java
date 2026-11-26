package com.securitymodel.application.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.securitymodel.application.assistant.AssistantException;
import com.securitymodel.application.assistant.ExperimentComparisonTool;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.note.ExperimentNote;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.ExperimentNoteMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentNoteServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ignoresClientMetricsAndStoresServerFacts() throws Exception {
        ExperimentNoteMapper noteMapper = mock(ExperimentNoteMapper.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        PreDefenseReportQueryService reports = mock(PreDefenseReportQueryService.class);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "admin", true));
        PreDefenseTask task = task(7L);
        when(guard.requireReadable(7L)).thenReturn(task);
        when(reports.getReport(7L)).thenReturn(report());
        doAnswer(invocation -> {
            ExperimentNote note = invocation.getArgument(0);
            note.setNoteId(11L);
            return 1;
        }).when(noteMapper).insert(any(ExperimentNote.class));

        var runRefs = mapper.createArrayNode();
        runRefs.addObject().put("taskId", "7").put("runId", "run-7").put("attempt", 1).put("ratio", "30");
        var tampered = mapper.createObjectNode().put("comparison",
                mapper.createObjectNode().put("cleanPurityPercent", 999.0));

        Map<String, Object> created = service(noteMapper, currentUser, guard, reports)
                .create(new ExperimentNoteRequest("tampered", "next", "request-key-001", runRefs, tampered));

        ArgumentCaptor<ExperimentNote> captor = ArgumentCaptor.forClass(ExperimentNote.class);
        org.mockito.Mockito.verify(noteMapper).insert(captor.capture());
        String stored = captor.getValue().getSnapshotJson();
        assertFalse(stored.contains("999"));
        assertTrue(stored.contains("server_fact"));
        assertTrue(stored.contains("100.0"));
        assertEquals("server_fact", created.get("snapshotSource"));
        assertEquals("verified", created.get("accessState"));
    }

    @Test
    void redactsAllMetricsAfterReportPermissionIsRevoked() {
        ExperimentNoteMapper noteMapper = mock(ExperimentNoteMapper.class);
        ExperimentNote note = note();
        when(noteMapper.findOwned(11L, 1L)).thenReturn(note);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "admin", true));
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        when(guard.requireReadable(7L)).thenThrow(new AccessDeniedException("无权访问该任务"));

        Map<String, Object> view = service(noteMapper, currentUser, guard, mock(PreDefenseReportQueryService.class))
                .getOwned(11L);

        assertEquals("revoked", view.get("accessState"));
        assertEquals(Boolean.TRUE, view.get("snapshot") instanceof com.fasterxml.jackson.databind.JsonNode node
                ? node.path("redacted").asBoolean() : false);
        assertFalse(view.toString().contains("100.0"));
        assertTrue(view.toString().contains("当前无权访问"));
    }

    @Test
    void rejectsRunReferenceWithoutReadPermissionAtSave() {
        ExperimentNoteMapper noteMapper = mock(ExperimentNoteMapper.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(1L, "admin", true));
        TaskAccessGuard guard = mock(TaskAccessGuard.class);
        when(guard.requireReadable(9L)).thenThrow(new AccessDeniedException("无权访问该任务"));
        var runRefs = mapper.createArrayNode();
        runRefs.addObject().put("taskId", "9").put("runId", "run-9").put("attempt", 1).put("ratio", "30");

        AssistantException exception = assertThrows(AssistantException.class, () ->
                service(noteMapper, currentUser, guard, mock(PreDefenseReportQueryService.class))
                        .create(new ExperimentNoteRequest("bad ref", "", "request-key-002", runRefs, null)));

        assertEquals("EXPERIMENT_NOTE_INVALID", exception.getCode());
        assertTrue(exception.getMessage().contains("不可用"));
    }

    private ExperimentNoteService service(ExperimentNoteMapper noteMapper, CurrentUserService currentUser,
                                          TaskAccessGuard guard, PreDefenseReportQueryService reports) {
        return new ExperimentNoteService(noteMapper, currentUser, guard, reports,
                mock(ExperimentComparisonTool.class), mapper);
    }

    private PreDefenseTask task(Long id) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(id);
        task.setStatus("success");
        task.setCurrentRunId("run-7");
        task.setCurrentAttempt(1);
        return task;
    }

    private Map<String, Object> report() {
        return Map.of("cleaningResult", Map.of(
                "run_id", "run-7", "attempt", 1,
                "metrics", Map.of(
                        "clean_purity_percent_by_isolation_ratio", Map.of("30", 100.0),
                        "selected_sample_count_by_isolation_ratio", Map.of("30", 15000)),
                "effective_config", Map.of("num_epochs", 5)));
    }

    private ExperimentNote note() {
        ExperimentNote note = new ExperimentNote();
        note.setNoteId(11L);
        note.setUserId(1L);
        note.setTitle("note");
        note.setAnalysisTime(LocalDateTime.now());
        note.setRunRefJson("[{\"taskId\":\"7\",\"runId\":\"run-7\",\"attempt\":1,\"ratio\":\"30\"}]");
        note.setSnapshotJson("""
                {"snapshotSource":"server_fact","facts":[{"taskId":"7","ratio":"30",
                "cleanPurityPercent":100.0,"sampleCount":15000}]}
                """);
        note.setStatus("active");
        return note;
    }
}
