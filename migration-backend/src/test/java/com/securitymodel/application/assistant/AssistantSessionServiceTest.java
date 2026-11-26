package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.assistant.AssistantSession;
import com.securitymodel.infrastructure.persistence.mapper.AssistantSessionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A stale comparison must not overwrite the last server-confirmed run selection. */
class AssistantSessionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void staleComparisonAfterRefreshKeepsOriginalRunBinding() throws Exception {
        AssistantSessionMapper sessionMapper = mock(AssistantSessionMapper.class);
        AssistantSession session = session();
        when(sessionMapper.findOwned(any(), any(), any())).thenReturn(session);
        doAnswer(invocation -> {
            session.setSummaryJson(invocation.getArgument(3));
            return 1;
        }).when(sessionMapper).appendTurnIfActive(any(), any(), any(), any(), any(), any());

        AssistantSessionService service = new AssistantSessionService(sessionMapper, mapper, 7);
        AssistantChatResponse.AssistantToolTrace confirmed = trace("ok", "run-a", 30);
        service.appendTurn(session, "对照 30%", "answered", "对照完成", List.of(ExperimentComparisonTool.NAME),
                List.of(), List.of(), List.of(confirmed));

        ObjectNode stale = mapper.createObjectNode();
        stale.put("kind", "comparison");
        stale.put("status", "stale");
        stale.put("primaryTaskId", "1");
        stale.putArray("baselineTaskIds").add("2");
        stale.put("ratio", "60");
        service.appendTurn(session, "换成 60% 再看", "answered", "原选择已失效，请重新选择",
                List.of(ExperimentComparisonTool.NAME), List.of(), List.of(),
                List.of(new AssistantChatResponse.AssistantToolTrace(
                        ExperimentComparisonTool.NAME, "运行已变化", stale, 10L, "ok")));

        var context = service.lastComparison(session);
        assertNotNull(context);
        assertEquals("30", context.path("ratio").asText());
        assertEquals("run-a", context.path("runs").get(0).path("runId").asText());
        assertEquals(1, context.path("runs").get(0).path("attempt").asInt());
        assertEquals(2, context.path("runs").size());
        assertEquals(2, mapper.readTree(session.getSummaryJson()).path("turns").size());
    }

    private AssistantChatResponse.AssistantToolTrace trace(String status, String runId, int ratio) {
        ObjectNode data = mapper.createObjectNode();
        data.put("kind", "comparison");
        data.put("status", status);
        data.put("primaryTaskId", "1");
        data.putArray("baselineTaskIds").add("2");
        data.put("ratio", String.valueOf(ratio));
        var runs = data.putArray("runs");
        runs.addObject().put("taskId", "1").put("runId", runId).put("attempt", 1);
        runs.addObject().put("taskId", "2").put("runId", "run-b").put("attempt", 1);
        return new AssistantChatResponse.AssistantToolTrace(
                ExperimentComparisonTool.NAME, "对照完成", data, 10L, "ok");
    }

    private AssistantSession session() {
        AssistantSession session = new AssistantSession();
        session.setSessionId(3L);
        session.setUserId(1L);
        session.setStatus(AssistantSession.ACTIVE);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusDays(7));
        return session;
    }
}
