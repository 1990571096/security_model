package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a no-write next-run draft from the selected attempt's effective configuration.
 * The client never supplies baseline parameters; the server reads the report, validates the
 * proposed configuration, and proves that the persisted-form preview differs only in epochs.
 */
@Component
public class DraftNextRunTool implements AssistantTool {

    static final String NAME = "draft_next_run";
    private static final Set<String> CHANGES = Set.of("num_epochs");
    private static final Set<String> ENVIRONMENT_FIELDS = Set.of("data_path");

    private final TaskAccessGuard taskAccessGuard;
    private final PreDefenseReportQueryService reportService;
    private final ValidateLddParametersTool validator;
    private final ObjectMapper mapper;

    public DraftNextRunTool(TaskAccessGuard taskAccessGuard, PreDefenseReportQueryService reportService,
                            ValidateLddParametersTool validator, ObjectMapper mapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.reportService = reportService;
        this.validator = validator;
        this.mapper = mapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.identifier(schema, "taskId", "作为基线的本人任务 ID", true);
        schema.withObject("/properties").putObject("runId").put("type", "string")
                .put("maxLength", 64).put("description", "可选，必须与所选运行一致");
        schema.withObject("/properties").putObject("attempt").put("type", "integer")
                .put("minimum", 1).put("description", "可选，必须与所选尝试一致");
        ObjectNode changes = schema.withObject("/properties").putObject("changes");
        changes.put("type", "object");
        changes.put("description", "只允许修改 num_epochs；缺省在基线基础上加 1");
        changes.put("additionalProperties", false);
        changes.putObject("properties").putObject("num_epochs")
                .put("type", "integer").put("minimum", 1).put("maximum", 200);
        return new AssistantToolDescriptor(NAME,
                "从指定运行的实际生效配置生成下一轮草案，只改训练轮数并复用现有参数校验；"
                        + "不创建任务、不写调度、不生成确认凭据", false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId", "runId", "attempt", "changes"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        PreDefenseTask task = taskAccessGuard.requireReadable(taskId);
        String expectedRunId = arguments.hasNonNull("runId") ? arguments.path("runId").asText() : null;
        Integer expectedAttempt = arguments.has("attempt") ? arguments.path("attempt").asInt() : null;

        Map<String, Object> report = reportService.getReport(taskId);
        Map<?, ?> cleaning = asMap(report.get("cleaningResult"));
        if (cleaning == null) {
            throw new AssistantException("BASELINE_CONFIG_UNAVAILABLE", HttpStatus.CONFLICT,
                    "任务 #" + taskId + " 没有可读取的生效配置，不能生成下一轮草案。");
        }
        String baselineRunId = text(cleaning.get("run_id"));
        Integer baselineAttempt = integer(cleaning.get("attempt"));
        if (expectedRunId != null && !expectedRunId.equals(baselineRunId)
                || expectedAttempt != null && !expectedAttempt.equals(baselineAttempt)) {
            throw new AssistantException("BASELINE_RUN_STALE", HttpStatus.CONFLICT,
                    "所选运行与任务 #" + taskId + " 当前报告不一致；请重新选择运行后再生成草案。");
        }
        if (baselineRunId != null && !baselineRunId.equals(task.getCurrentRunId())
                || baselineAttempt != null && !baselineAttempt.equals(task.getCurrentAttempt())) {
            throw new AssistantException("BASELINE_RUN_STALE", HttpStatus.CONFLICT,
                    "任务 #" + taskId + " 的报告不是当前尝试，不能作为下一轮基线；请重新读取当前运行。");
        }
        ObjectNode base = readEffectiveConfig(cleaning.get("effective_config"));
        if (base.isEmpty()) {
            throw new AssistantException("BASELINE_CONFIG_UNAVAILABLE", HttpStatus.CONFLICT,
                    "任务 #" + taskId + " 的报告没有 effective_config，无法证明新表单只改训练轮数。");
        }
        if (!base.path("num_epochs").isInt()) {
            throw new AssistantException("BASELINE_EPOCHS_MISSING", HttpStatus.CONFLICT,
                    "任务 #" + taskId + " 的生效配置缺少 num_epochs，不能推断下一轮轮数。");
        }

        ObjectNode changes = arguments.has("changes")
                ? AssistantArgs.requireObjectArgument(arguments, "changes").deepCopy()
                : mapper.createObjectNode();
        Iterator<String> names = changes.fieldNames();
        while (names.hasNext()) {
            if (!CHANGES.contains(names.next())) {
                throw invalid("草案只允许修改 num_epochs，其他变量应一次只改一项");
            }
        }
        int beforeEpochs = base.path("num_epochs").asInt();
        int afterEpochs = changes.path("num_epochs").asInt(beforeEpochs + 1);
        if (afterEpochs < 1 || afterEpochs > 200) {
            throw invalid("num_epochs 必须在 1 到 200 之间");
        }
        ObjectNode after = base.deepCopy();
        after.put("num_epochs", afterEpochs);
        ENVIRONMENT_FIELDS.forEach(after::remove);

        ObjectNode validationArgs = mapper.createObjectNode();
        validationArgs.put("datasetId", String.valueOf(task.getDatasetId()));
        validationArgs.put("algorithmId", String.valueOf(task.getAlgorithmId()));
        if (task.getHyperId() != null) {
            validationArgs.put("hyperId", String.valueOf(task.getHyperId()));
        }
        validationArgs.set("customParams", after.deepCopy());
        AssistantToolResult validation = validator.execute(validationArgs);
        boolean valid = validation.data().path("valid").asBoolean(false);
        JsonNode normalized = validation.data().path("normalizedParameters");
        ObjectNode finalPreview = normalized.isObject() ? ((ObjectNode) normalized).deepCopy() : after.deepCopy();
        ENVIRONMENT_FIELDS.forEach(finalPreview::remove);

        List<String> changedFields = changedFields(base, finalPreview);
        boolean onlyEpochsChanged = changedFields.size() == 1 && "num_epochs".equals(changedFields.get(0));
        boolean reproducible = valid && onlyEpochsChanged;

        ObjectNode data = mapper.createObjectNode();
        data.put("kind", "next_run_draft");
        data.put("taskId", task.getTaskId().toString());
        data.put("baselineTaskName", task.getTaskName());
        data.put("baselineRunId", baselineRunId);
        data.put("baselineAttempt", baselineAttempt == null ? null : baselineAttempt);
        data.put("baselineSource", "cleaningResult.effective_config");
        data.put("datasetId", task.getDatasetId() == null ? null : task.getDatasetId().toString());
        data.put("algorithmId", task.getAlgorithmId() == null ? null : task.getAlgorithmId().toString());
        data.put("hyperId", task.getHyperId() == null ? null : task.getHyperId().toString());
        data.put("changedField", "num_epochs");
        data.put("before", beforeEpochs);
        data.put("after", afterEpochs);
        data.set("proposedParams", finalPreview);
        data.set("validation", validation.data().deepCopy());
        data.put("valid", valid);
        data.put("reproducible", reproducible);
        ObjectNode deltaCheck = data.putObject("deltaCheck");
        deltaCheck.put("onlyNumEpochsChanged", onlyEpochsChanged);
        deltaCheck.put("baselineFields", base.size());
        deltaCheck.put("previewFields", finalPreview.size());
        var changed = deltaCheck.putArray("changedFields");
        changedFields.forEach(changed::add);
        var omitted = deltaCheck.putArray("omittedEnvironmentFields");
        ENVIRONMENT_FIELDS.forEach(omitted::add);
        data.put("formPath", "/tasks?prefillTaskId=" + task.getTaskId()
                + (baselineRunId == null ? "" : "&prefillRunId=" + baselineRunId)
                + (baselineAttempt == null ? "" : "&prefillAttempt=" + baselineAttempt));
        data.put("sideEffect", "none");
        data.put("confirmationCreated", false);

        String summary = "已基于任务 #" + task.getTaskId()
                + (baselineRunId == null ? "" : " 运行 " + baselineRunId + " / #" + baselineAttempt)
                + " 的实际生效配置生成草案：" + beforeEpochs + " → " + afterEpochs + " epochs；参数校验"
                + (valid ? "通过" : "未通过（" + validation.data().path("code").asText() + "）")
                + (onlyEpochsChanged ? "，与生效配置相比只改训练轮数" : "，但配置差异不止训练轮数，不能视为单变量草案")
                + "；未创建任务。";
        return new AssistantToolResult(summary, data, null, null, task.getTaskId(),
                baselineRunId, null);
    }

    private ObjectNode readEffectiveConfig(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return mapper.createObjectNode();
        }
        JsonNode node = mapper.valueToTree(map);
        return node.isObject() ? ((ObjectNode) node).deepCopy() : mapper.createObjectNode();
    }

    private List<String> changedFields(ObjectNode baseline, ObjectNode preview) {
        Set<String> keys = new LinkedHashSet<>();
        baseline.fieldNames().forEachRemaining(keys::add);
        preview.fieldNames().forEachRemaining(keys::add);
        keys.removeAll(ENVIRONMENT_FIELDS);
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!Objects.equals(canonical(baseline.get(key)), canonical(preview.get(key)))) {
                changed.add(key);
            }
        }
        return changed;
    }

    private String canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "<missing>";
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        return node.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static AssistantException invalid(String message) {
        return new AssistantException("ASSISTANT_TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST, message);
    }
}
