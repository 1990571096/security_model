package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.domain.assistant.AssistantSession;
import com.securitymodel.infrastructure.persistence.mapper.AssistantSessionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Seven-day, user-owned, structured conversation summary. No full prompt is stored. */
@Service
public class AssistantSessionService {

    private static final int MAX_TURNS = 8;
    private static final int MAX_SUMMARY_CHARS = 16_000;

    private final AssistantSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;
    private final int sessionTtlDays;

    public AssistantSessionService(
            AssistantSessionMapper sessionMapper,
            ObjectMapper objectMapper,
            @Value("${security-model.assistant.session-ttl-days:7}") int sessionTtlDays) {
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
        this.sessionTtlDays = Math.max(1, sessionTtlDays);
    }

    public AssistantSession getOrCreate(Long sessionId, Long userId, String question) {
        if (sessionId != null) {
            AssistantSession existing = sessionMapper.findOwned(sessionId, userId, LocalDateTime.now());
            if (existing == null) {
                throw new AssistantException("ASSISTANT_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "会话不存在、已删除或超过 7 天保留期");
            }
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        AssistantSession session = new AssistantSession();
        session.setUserId(userId);
        session.setTitle(titleFrom(question));
        session.setStatus(AssistantSession.ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(now.plusDays(sessionTtlDays));
        sessionMapper.insert(session);
        return session;
    }

    public AssistantSession requireOwned(Long sessionId, Long userId) {
        AssistantSession session = sessionMapper.findOwned(sessionId, userId, LocalDateTime.now());
        if (session == null) {
            throw new AssistantException("ASSISTANT_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "会话不存在、已删除或超过 7 天保留期");
        }
        return session;
    }

    public boolean appendTurn(AssistantSession session, String question, String answerStatus,
                              String answer, List<String> toolNames, List<Long> confirmationIds,
                              List<String> citationIds) {
        return appendTurn(session, question, answerStatus, answer, toolNames, confirmationIds,
                citationIds, List.of());
    }

    public boolean appendTurn(AssistantSession session, String question, String answerStatus,
                              String answer, List<String> toolNames, List<Long> confirmationIds,
                              List<String> citationIds, List<AssistantChatResponse.AssistantToolTrace> traces) {
        // Retry a few times because two chats may finish at once. The update only succeeds
        // when the stored summary is still the one this attempt merged from; it never writes
        // status back, so a deleted session cannot be resurrected.
        for (int attempt = 0; attempt < 3; attempt++) {
            AssistantSession current = sessionMapper.findOwned(session.getSessionId(),
                    session.getUserId(), LocalDateTime.now());
            if (current == null) {
                return false;
            }
            String summary = mergeSummary(current.getSummaryJson(), question, answerStatus,
                    answer, toolNames, confirmationIds, citationIds, traces);
            LocalDateTime now = LocalDateTime.now();
            int updated = sessionMapper.appendTurnIfActive(session.getSessionId(), session.getUserId(),
                    current.getSummaryJson(), summary, now, now.plusDays(sessionTtlDays));
            if (updated == 1) {
                session.setSummaryJson(summary);
                session.setUpdatedAt(now);
                session.setExpiresAt(now.plusDays(sessionTtlDays));
                return true;
            }
        }
        return false;
    }

    private String mergeSummary(String rawSummary, String question, String answerStatus,
                                String answer, List<String> toolNames, List<Long> confirmationIds,
                                List<String> citationIds, List<AssistantChatResponse.AssistantToolTrace> traces) {
        ObjectNode root = readSummary(rawSummary);
        ArrayNode turns = root.has("turns") && root.get("turns").isArray()
                ? (ArrayNode) root.get("turns") : root.putArray("turns");
        ObjectNode turn = turns.addObject();
        turn.put("question", KnowledgeSanitizer.forModel(question, 500));
        turn.put("status", answerStatus);
        turn.put("answer", KnowledgeSanitizer.forModel(answer == null ? "" : answer, 1_500));
        ArrayNode tools = turn.putArray("tools");
        toolNames.forEach(tools::add);
        if (!traces.isEmpty()) {
            ArrayNode activity = turn.putArray("toolResults");
            for (var trace : traces) {
                ObjectNode item = activity.addObject();
                item.put("toolName", trace.toolName());
                item.put("status", trace.status());
                item.put("summary", KnowledgeSanitizer.forModel(trace.summary(), 180));
                if (trace.durationMs() != null) {
                    item.put("durationMs", trace.durationMs());
                }
                if ("answered".equals(answerStatus) && "ok".equals(trace.status()) && trace.data() != null
                        && ("get_my_task_summary".equals(trace.toolName())
                        || "get_my_report_summary".equals(trace.toolName()))) {
                    String taskId = trace.data().path("taskId").asText();
                    if (taskId.matches("[1-9][0-9]{0,18}")) {
                        turn.put("readTaskId", taskId);
                    }
                }
            }
        }
        ArrayNode confirms = turn.putArray("confirmationIds");
        confirmationIds.forEach(confirms::add);
        ArrayNode citations = turn.putArray("citationIds");
        citationIds.forEach(citations::add);
        turn.put("at", LocalDateTime.now().toString());
        rememberComparison(root, traces);

        while (turns.size() > MAX_TURNS) {
            turns.remove(0);
        }
        String summary = root.toString();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            while (turns.size() > 1 && summary.length() > MAX_SUMMARY_CHARS) {
                turns.remove(0);
                summary = root.toString();
            }
        }
        return summary;
    }

    public String lastKnowledgeQuestion(AssistantSession session) {
        JsonNode turns = readSummary(session.getSummaryJson()).path("turns");
        for (int index = turns.size() - 1; index >= 0; index--) {
            JsonNode turn = turns.path(index);
            if (!"answered".equals(turn.path("status").asText())) {
                continue;
            }
            for (JsonNode tool : turn.path("tools")) {
                String question = turn.path("question").asText("");
                if ((AssistantChatService.KNOWLEDGE_TOOL.equals(tool.asText())
                        || ExperimentMetricTool.NAME.equals(tool.asText())) && !question.isBlank()
                        && !AssistantChatService.isKnowledgeFollowUp(question)
                        && !AssistantChatService.isCapabilityQuestion(question)
                        && !AssistantChatService.isUnspecifiedRecordsQuestion(question)) {
                    return question;
                }
            }
        }
        return null;
    }

    public String lastReadTaskId(AssistantSession session) {
        JsonNode turns = readSummary(session.getSummaryJson()).path("turns");
        return turns.path(turns.size() - 1).path("readTaskId").asText(null);
    }

    /** Last server-confirmed experiment comparison, used only for a follow-up such as "只看 30%". */
    public JsonNode lastComparison(AssistantSession session) {
        JsonNode context = readSummary(session.getSummaryJson()).path("experimentContext");
        return context.isObject() ? context : null;
    }

    private void rememberComparison(ObjectNode root, List<AssistantChatResponse.AssistantToolTrace> traces) {
        for (AssistantChatResponse.AssistantToolTrace trace : traces) {
            JsonNode data = trace.data();
            if (data == null || !"ok".equals(trace.status())
                    || !ExperimentComparisonTool.NAME.equals(trace.toolName())
                    || !"comparison".equals(data.path("kind").asText())
                    || !"ok".equals(data.path("status").asText())) {
                continue;
            }
            ObjectNode context = root.putObject("experimentContext");
            context.put("primaryTaskId", data.path("primaryTaskId").asText());
            context.set("baselineTaskIds", data.path("baselineTaskIds").deepCopy());
            context.put("ratio", data.path("ratio").asText());
            JsonNode runs = data.path("runs");
            if (runs.isArray() && !runs.isEmpty()) {
                ArrayNode bound = context.putArray("runs");
                for (JsonNode run : runs) {
                    bound.addObject().put("taskId", run.path("taskId").asText())
                            .put("runId", run.path("runId").asText(null))
                            .put("attempt", run.path("attempt").isInt() ? run.path("attempt").asInt() : null);
                }
                JsonNode primary = runs.get(0);
                context.put("primaryRunId", primary.path("runId").asText(null));
                context.put("primaryAttempt", primary.path("attempt").isInt()
                        ? primary.path("attempt").asInt() : null);
            }
            context.put("updatedAt", LocalDateTime.now().toString());
        }
    }

    public List<Map<String, Object>> listMine(Long userId, int limit) {
        return sessionMapper.findByUser(userId, LocalDateTime.now(), Math.max(1, Math.min(50, limit)))
                .stream().map(this::toView).toList();
    }

    public Map<String, Object> getOwnedView(Long sessionId, Long userId) {
        AssistantSession session = requireOwned(sessionId, userId);
        Map<String, Object> view = toView(session);
        view.put("summary", readSummary(session.getSummaryJson()));
        return view;
    }

    public void deleteOwned(Long sessionId, Long userId) {
        int updated = sessionMapper.softDelete(sessionId, userId, LocalDateTime.now());
        if (updated != 1) {
            throw new AssistantException("ASSISTANT_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "会话不存在或已删除");
        }
    }

    public int deleteExpired(int limit) {
        return sessionMapper.deleteExpired(LocalDateTime.now(), Math.max(1, limit));
    }

    private Map<String, Object> toView(AssistantSession session) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", session.getSessionId());
        view.put("title", session.getTitle());
        view.put("createdAt", session.getCreatedAt());
        view.put("updatedAt", session.getUpdatedAt());
        view.put("expiresAt", session.getExpiresAt());
        return view;
    }

    private ObjectNode readSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String titleFrom(String question) {
        String sanitized = KnowledgeSanitizer.forModel(question == null ? "" : question, 60).trim();
        return sanitized.isEmpty() ? "实验助手会话" : sanitized;
    }
}
