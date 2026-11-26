package com.securitymodel.application.note;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.assistant.AssistantException;
import com.securitymodel.application.assistant.AssistantToolResult;
import com.securitymodel.application.assistant.ExperimentComparisonTool;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.note.ExperimentNote;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.ExperimentNoteMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * User-owned private experiment notes. A note snapshot is built and verified on the server from
 * the selected runs; client-provided snapshot content is never trusted. Reads and Markdown exports
 * re-check every run reference against the current owner and attempt.
 */
@Service
public class ExperimentNoteService {

    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final int MAX_RUNS = 3;
    private static final int MAX_SNAPSHOT_BYTES = 20_000;

    private final ExperimentNoteMapper mapper;
    private final CurrentUserService currentUserService;
    private final TaskAccessGuard taskAccessGuard;
    private final PreDefenseReportQueryService reportService;
    private final ExperimentComparisonTool comparisonTool;
    private final ObjectMapper objectMapper;

    public ExperimentNoteService(ExperimentNoteMapper mapper, CurrentUserService currentUserService,
                                 TaskAccessGuard taskAccessGuard, PreDefenseReportQueryService reportService,
                                 ExperimentComparisonTool comparisonTool, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.currentUserService = currentUserService;
        this.taskAccessGuard = taskAccessGuard;
        this.reportService = reportService;
        this.comparisonTool = comparisonTool;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(ExperimentNoteRequest request) {
        Long userId = currentUserService.requireStableIdentity().userId();
        String requestKey = request.requestKey();
        if (requestKey == null || !REQUEST_KEY.matcher(requestKey).matches()) {
            throw invalid("保存请求缺少有效的幂等键");
        }
        ExperimentNote existing = mapper.findByRequestKey(userId, requestKey);
        if (existing != null) {
            return view(existing, true);
        }
        String title = KnowledgeSanitizer.forModel(request.title(), 120);
        if (title.isBlank()) {
            title = "实验对照笔记";
        }
        List<VerifiedRun> verified = verifyRequestedRuns(request.runRefs());
        String ratio = verified.get(0).ratio();
        ObjectNode snapshot = buildFactSnapshot(verified, ratio);
        if (snapshot.toString().length() > MAX_SNAPSHOT_BYTES) {
            throw invalid("服务端生成的笔记快照超过长度上限");
        }
        String next = KnowledgeSanitizer.forModel(request.nextStep(), 500);
        LocalDateTime now = LocalDateTime.now();
        ExperimentNote note = new ExperimentNote();
        note.setUserId(userId);
        note.setTitle(title);
        note.setAnalysisTime(now);
        note.setRunRefJson(runRefs(verified).toString());
        note.setSnapshotJson(snapshot.toString());
        note.setNextStep(next.isBlank() ? null : next);
        note.setRequestKey(requestKey);
        note.setStatus("active");
        note.setCreateTime(now);
        note.setUpdateTime(now);
        try {
            mapper.insert(note);
        } catch (DuplicateKeyException exception) {
            ExperimentNote replay = mapper.findByRequestKey(userId, requestKey);
            if (replay == null) {
                throw invalid("保存冲突，请重试");
            }
            return view(replay, true);
        }
        return view(note, false);
    }

    public List<Map<String, Object>> listMine(int limit) {
        Long userId = currentUserService.requireStableIdentity().userId();
        return mapper.findByUser(userId, Math.max(1, Math.min(50, limit))).stream()
                .map(note -> view(note, false)).toList();
    }

    public Map<String, Object> getOwned(Long noteId) {
        return view(requireOwned(noteId), false);
    }

    @Transactional
    public Map<String, Object> update(Long noteId, String title, String nextStep) {
        ExperimentNote note = requireOwned(noteId);
        String safeTitle = KnowledgeSanitizer.forModel(title, 120);
        if (safeTitle.isBlank()) {
            throw invalid("标题不能为空");
        }
        note.setTitle(safeTitle);
        note.setNextStep(KnowledgeSanitizer.forModel(nextStep, 500));
        note.setUpdateTime(LocalDateTime.now());
        mapper.updateById(note);
        return view(note, false);
    }

    public void delete(Long noteId) {
        Long userId = currentUserService.requireStableIdentity().userId();
        if (mapper.softDelete(noteId, userId, LocalDateTime.now()) != 1) {
            throw notFound();
        }
    }

    public String markdown(Long noteId) {
        ExperimentNote note = requireOwned(noteId);
        Revalidation revalidation = revalidate(note);
        JsonNode snapshot = revalidation.snapshot();
        StringBuilder markdown = new StringBuilder("# ").append(note.getTitle()).append("\n\n")
                .append("- 分析时间：").append(note.getAnalysisTime()).append("\n")
                .append("- 拥有者：当前用户\n")
                .append("- 快照来源：").append(revalidation.source()).append("\n");
        JsonNode runs = readArray(note.getRunRefJson());
        markdown.append("\n## 运行引用\n\n");
        for (JsonNode run : runs) {
            markdown.append("- 任务 #").append(run.path("taskId").asText())
                    .append("，运行 ").append(run.path("runId").asText("未知"))
                    .append("，第 ").append(run.path("attempt").asText("未知")).append(" 次尝试");
            if (run.hasNonNull("ratio")) {
                markdown.append("，保留比例 ").append(run.path("ratio").asText()).append("%");
            }
            markdown.append("\n");
        }
        if (revalidation.accessState() != AccessState.VERIFIED) {
            markdown.append("\n> 当前权限或运行已变化，原快照中的指标已省略：\n");
            revalidation.notes().forEach(noteMessage ->
                    markdown.append("> - ").append(noteMessage).append("\n"));
        }
        JsonNode comparison = snapshot.path("comparison");
        if (comparison.isObject() && revalidation.accessState() == AccessState.VERIFIED) {
            markdown.append("\n## 对照结果（服务端事实快照）\n\n");
            markdown.append("保留比例：").append(comparison.path("ratio").asText("未记录")).append("%\n\n");
            for (JsonNode run : comparison.path("runs")) {
                markdown.append("- 任务 #").append(run.path("taskId").asText())
                        .append("：纯度 ").append(run.path("cleanPurityPercentByIsolationRatio")
                                .path(comparison.path("ratio").asText()).asText("未采集"))
                        .append("%；样本 ").append(run.path("selectedSampleCountByIsolationRatio")
                                .path(comparison.path("ratio").asText()).asText("未采集")).append("\n");
            }
            markdown.append("\n");
            for (JsonNode delta : comparison.path("deltas")) {
                markdown.append("- #").append(delta.path("primaryTaskId").asText())
                        .append(" 对 #").append(delta.path("baselineTaskId").asText())
                        .append("：纯度差 ").append(delta.path("cleanPurityPercentagePoints").asText("不可计算"))
                        .append(" 个百分点；样本数差 ").append(delta.path("sampleCountDifference").asText("不可计算"))
                        .append(" 个\n");
            }
            if (!comparison.path("gaps").isEmpty()) {
                markdown.append("\n### 证据缺口\n\n");
                for (JsonNode gap : comparison.path("gaps")) {
                    markdown.append("- ").append(gap.path("message").asText()).append("\n");
                }
            }
        } else if (snapshot.path("facts").isArray()
                && revalidation.accessState() == AccessState.VERIFIED) {
            markdown.append("\n## 运行事实（服务端快照）\n\n");
            for (JsonNode fact : snapshot.path("facts")) {
                markdown.append("- 任务 #").append(fact.path("taskId").asText())
                        .append(" · ").append(fact.path("ratio").asText()).append("%")
                        .append("：纯度 ").append(fact.path("cleanPurityPercent").asText("未采集"))
                        .append("%；样本 ").append(fact.path("sampleCount").asText("未采集")).append("\n");
            }
        }
        if (note.getNextStep() != null && !note.getNextStep().isBlank()) {
            markdown.append("\n## 下一步\n\n").append(note.getNextStep()).append("\n");
        }
        markdown.append("\n> 导出时已按当前权限重新校验每个运行引用；指标只来自服务端事实快照。\n");
        return markdown.toString();
    }

    private List<VerifiedRun> verifyRequestedRuns(JsonNode runRefs) {
        if (runRefs == null || !runRefs.isArray() || runRefs.isEmpty() || runRefs.size() > MAX_RUNS) {
            throw invalid("笔记需要 1 至 3 个运行引用");
        }
        List<VerifiedRun> verified = new ArrayList<>();
        String ratio = null;
        for (JsonNode run : runRefs) {
            if (!run.isObject()) {
                throw invalid("运行引用必须是对象");
            }
            String taskIdText = run.path("taskId").asText();
            if (!taskIdText.matches("[1-9][0-9]{0,18}")) {
                throw invalid("运行引用中的任务 ID 无效");
            }
            Long taskId = Long.parseLong(taskIdText);
            String requestedRunId = run.hasNonNull("runId") ? run.path("runId").asText() : null;
            Integer requestedAttempt = run.path("attempt").isInt() ? run.path("attempt").asInt() : null;
            String runRatio = run.hasNonNull("ratio") ? run.path("ratio").asText() : null;
            if (runRatio != null && !runRatio.matches("(?:[0-9]|[1-9][0-9]|100)")) {
                throw invalid("运行引用中的保留比例无效");
            }
            if (ratio == null) {
                ratio = runRatio;
            } else if (runRatio != null && !ratio.equals(runRatio)) {
                throw invalid("同一份笔记的运行引用必须使用相同保留比例");
            }
            PreDefenseTask task;
            try {
                task = taskAccessGuard.requireReadable(taskId);
            } catch (AccessDeniedException | com.securitymodel.application.task.TaskCommandException exception) {
                throw invalid("运行引用包含不可用的任务 #" + taskId);
            }
            Map<String, Object> report = reportService.getReport(taskId);
            Map<?, ?> cleaning = asMap(report.get("cleaningResult"));
            String actualRunId = cleaning == null ? null : text(cleaning.get("run_id"));
            Integer actualAttempt = cleaning == null ? null : integer(cleaning.get("attempt"));
            if (actualRunId == null || actualAttempt == null) {
                throw invalid("任务 #" + taskId + " 没有可校验的运行报告");
            }
            if (requestedRunId != null && !requestedRunId.equals(actualRunId)
                    || requestedAttempt != null && !requestedAttempt.equals(actualAttempt)) {
                throw invalid("任务 #" + taskId + " 的报告运行已变化，请重新选择运行");
            }
            if (!actualRunId.equals(task.getCurrentRunId()) || !actualAttempt.equals(task.getCurrentAttempt())) {
                throw invalid("任务 #" + taskId + " 的报告不是当前尝试，请重新读取当前运行");
            }
            verified.add(new VerifiedRun(taskId, actualRunId, actualAttempt, ratio, report, cleaning));
        }
        if (ratio == null) {
            ratio = "30";
        }
        return verified;
    }

    private ObjectNode buildFactSnapshot(List<VerifiedRun> runs, String ratio) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("snapshotSource", "server_fact");
        snapshot.put("generatedAt", LocalDateTime.now().toString());
        snapshot.put("ratio", ratio);
        ArrayNode facts = snapshot.putArray("facts");
        for (VerifiedRun run : runs) {
            facts.add(fact(run, ratio));
        }
        if (runs.size() >= 2) {
            var args = objectMapper.createObjectNode();
            var ids = args.putArray("taskIds");
            var refs = args.putArray("runs");
            for (VerifiedRun run : runs) {
                ids.add(run.taskId().toString());
                refs.addObject().put("taskId", run.taskId().toString())
                        .put("runId", run.runId()).put("attempt", run.attempt());
            }
            args.put("isolationRatio", ratio);
            args.put("focus", "subset");
            AssistantToolResult result = comparisonTool.execute(args);
            if (!"ok".equals(result.data().path("status").asText())) {
                throw invalid("运行引用已变化，服务端无法生成可信对照快照");
            }
            snapshot.set("comparison", result.data());
        }
        return snapshot;
    }

    private ObjectNode fact(VerifiedRun run, String ratio) {
        Map<?, ?> metrics = asMap(run.cleaning().get("metrics"));
        JsonNode purity = objectMapper.valueToTree(
                metrics == null ? Map.of() : metrics.get("clean_purity_percent_by_isolation_ratio"));
        JsonNode counts = objectMapper.valueToTree(
                metrics == null ? Map.of() : metrics.get("selected_sample_count_by_isolation_ratio"));
        ObjectNode fact = objectMapper.createObjectNode();
        fact.put("taskId", run.taskId().toString());
        fact.put("runId", run.runId());
        fact.put("attempt", run.attempt());
        fact.put("ratio", ratio);
        fact.set("cleanPurityPercent", purity.path(ratio));
        fact.set("sampleCount", counts.path(ratio));
        fact.put("reportEndpoint", "GET /api/pre-defense/report/" + run.taskId());
        return fact;
    }

    private ArrayNode runRefs(List<VerifiedRun> runs) {
        ArrayNode refs = objectMapper.createArrayNode();
        for (VerifiedRun run : runs) {
            refs.addObject().put("taskId", run.taskId().toString())
                    .put("runId", run.runId())
                    .put("attempt", run.attempt())
                    .put("ratio", run.ratio());
        }
        return refs;
    }

    private ExperimentNote requireOwned(Long noteId) {
        Long userId = currentUserService.requireStableIdentity().userId();
        ExperimentNote note = mapper.findOwned(noteId, userId);
        if (note == null) {
            throw notFound();
        }
        return note;
    }

    private Map<String, Object> view(ExperimentNote note, boolean replayed) {
        Revalidation revalidation = revalidate(note);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("noteId", note.getNoteId().toString());
        view.put("title", note.getTitle());
        view.put("analysisTime", note.getAnalysisTime());
        view.put("updateTime", note.getUpdateTime());
        view.put("nextStep", note.getNextStep());
        view.put("replayed", replayed);
        view.put("snapshot", revalidation.snapshot());
        view.put("snapshotSource", revalidation.source());
        view.put("accessState", revalidation.accessState().name().toLowerCase());
        view.put("unavailableRefs", revalidation.unavailableRefs());
        view.put("accessNotes", revalidation.notes());
        view.put("runRefs", readArray(note.getRunRefJson()));
        return view;
    }

    private Revalidation revalidate(ExperimentNote note) {
        JsonNode stored = read(note.getSnapshotJson());
        String source = stored.path("snapshotSource").asText("legacy_client");
        ArrayNode refs = readArray(note.getRunRefJson());
        List<String> unavailable = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean allVerified = true;
        int accessible = 0;
        for (JsonNode ref : refs) {
            String taskId = ref.path("taskId").asText();
            if (!taskId.matches("[1-9][0-9]{0,18}")) {
                allVerified = false;
                unavailable.add(taskId);
                notes.add("运行引用格式无效，已省略快照");
                continue;
            }
            try {
                PreDefenseTask task = taskAccessGuard.requireReadable(Long.parseLong(taskId));
                accessible++;
                Map<String, Object> report = reportService.getReport(Long.parseLong(taskId));
                Map<?, ?> cleaning = asMap(report.get("cleaningResult"));
                String actualRunId = cleaning == null ? null : text(cleaning.get("run_id"));
                Integer actualAttempt = cleaning == null ? null : integer(cleaning.get("attempt"));
                String storedRunId = ref.hasNonNull("runId") ? ref.path("runId").asText() : null;
                Integer storedAttempt = ref.path("attempt").isInt() ? ref.path("attempt").asInt() : null;
                if (actualRunId == null || actualAttempt == null
                        || storedRunId != null && !storedRunId.equals(actualRunId)
                        || storedAttempt != null && !storedAttempt.equals(actualAttempt)
                        || !actualRunId.equals(task.getCurrentRunId())
                        || !actualAttempt.equals(task.getCurrentAttempt())) {
                    allVerified = false;
                    unavailable.add(taskId);
                    notes.add("任务 #" + taskId + " 的当前运行或尝试已变化，快照指标已省略");
                }
            } catch (AccessDeniedException | com.securitymodel.application.task.TaskCommandException exception) {
                allVerified = false;
                unavailable.add(taskId);
                notes.add("任务 #" + taskId + " 当前无权访问，快照指标已省略");
            } catch (AssistantException exception) {
                allVerified = false;
                unavailable.add(taskId);
                notes.add("任务 #" + taskId + " 的报告当前不可用，快照指标已省略");
            } catch (RuntimeException exception) {
                allVerified = false;
                unavailable.add(taskId);
                notes.add("任务 #" + taskId + " 当前无法完成证据复核，快照指标已省略");
            }
        }
        if (!"server_fact".equals(source)) {
            allVerified = false;
            notes.add("该笔记保存于服务端事实快照启用前，原客户端快照不作为证据展示；请重新保存");
        }
        AccessState state = allVerified ? AccessState.VERIFIED
                : refs.isEmpty() || accessible == 0 ? AccessState.REVOKED : AccessState.PARTIAL;
        JsonNode visible = allVerified ? stored : objectMapper.createObjectNode()
                .put("snapshotSource", source)
                .put("redacted", true)
                .put("generatedAt", LocalDateTime.now().toString())
                .put("message", "按当前权限重新校验后，原快照指标已省略");
        return new Revalidation(visible, "server_fact".equals(source) ? "server_fact" : "legacy_client",
                state, unavailable, notes);
    }

    private JsonNode read(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private ArrayNode readArray(String raw) {
        JsonNode node = read(raw);
        return node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private AssistantException invalid(String message) {
        return new AssistantException("EXPERIMENT_NOTE_INVALID", HttpStatus.BAD_REQUEST, message);
    }

    private AssistantException notFound() {
        return new AssistantException("EXPERIMENT_NOTE_NOT_FOUND", HttpStatus.NOT_FOUND,
                "笔记不存在或不属于当前用户");
    }

    private enum AccessState { VERIFIED, PARTIAL, REVOKED }

    private record VerifiedRun(Long taskId, String runId, Integer attempt, String ratio,
                               Map<String, Object> report, Map<?, ?> cleaning) {
    }

    private record Revalidation(JsonNode snapshot, String source, AccessState accessState,
                                List<String> unavailableRefs, List<String> notes) {
    }
}
