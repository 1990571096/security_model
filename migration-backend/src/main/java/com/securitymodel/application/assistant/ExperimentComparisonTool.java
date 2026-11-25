package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads two or more owned reports and compares only metrics recorded by the run itself.
 * Missing values stay missing; this tool never estimates, interpolates or attributes cause.
 */
@Component
public class ExperimentComparisonTool implements AssistantTool {

    static final String NAME = "compare_experiment_runs";
    private static final int MAX_RUNS = 3;
    private static final Set<String> COMPARED_CONFIG = Set.of("dataset", "seed", "batch_size", "lr_poison",
            "trigger_type", "poisoned_mode", "trigger_label", "poisoned_portion", "num_epochs", "r",
            "alpha1", "alpha2");

    private final TaskAccessGuard taskAccessGuard;
    private final PreDefenseReportQueryService reportService;
    private final ObjectMapper objectMapper;

    public ExperimentComparisonTool(TaskAccessGuard taskAccessGuard,
                                    PreDefenseReportQueryService reportService,
                                    ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        ObjectNode taskIds = schema.withObject("/properties").putObject("taskIds");
        taskIds.put("type", "array");
        taskIds.put("description", "2 至 3 个本人有权读取的实验任务 ID（十进制字符串），第一个是主运行");
        taskIds.put("minItems", 2);
        taskIds.put("maxItems", MAX_RUNS);
        taskIds.putObject("items").put("type", "string").put("pattern", "^[0-9]{1,19}$").put("maxLength", 19);
        ((ArrayNode) schema.get("required")).add("taskIds");
        ObjectNode runs = schema.withObject("/properties").putObject("runs");
        runs.put("type", "array");
        runs.put("description", "可选的运行绑定，顺序与 taskIds 一致；提供后报告必须仍对应该 runId/attempt");
        runs.put("minItems", 2);
        runs.put("maxItems", MAX_RUNS);
        ObjectNode run = runs.putObject("items");
        run.put("type", "object");
        run.put("additionalProperties", false);
        ObjectNode properties = run.putObject("properties");
        properties.putObject("taskId").put("type", "string")
                .put("pattern", "^[0-9]{1,19}$").put("maxLength", 19);
        properties.putObject("runId").put("type", "string").put("maxLength", 64);
        properties.putObject("attempt").put("type", "integer").put("minimum", 1);
        run.putArray("required").add("taskId");
        AssistantSchemas.text(schema, "isolationRatio", "相同保留比例，例如 30 表示 30%；缺省 30", 12, false);
        AssistantSchemas.text(schema, "focus", "可选关注点：subset / purity / sample_count", 32, false);
        return new AssistantToolDescriptor(NAME,
                "按同一保留比例对齐两次本人实验运行，读取报告中的清洗纯度与保留样本数，"
                        + "给出逐项口径、差值和证据出处；缺失指标不会补值",
                false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskIds", "runs", "isolationRatio", "focus"));
        JsonNode ids = arguments.get("taskIds");
        if (ids == null || !ids.isArray() || ids.size() < 2 || ids.size() > MAX_RUNS) {
            throw invalid("taskIds 需要 2 至 3 个任务 ID");
        }
        List<Long> taskIds = new ArrayList<>();
        for (JsonNode item : ids) {
            if (!item.isTextual() || !item.asText().matches("[0-9]{1,19}")) {
                throw invalid("taskIds 只能包含十进制字符串");
            }
            long id = Long.parseLong(item.asText());
            if (taskIds.contains(id)) {
                throw invalid("同一运行不能重复比较");
            }
            taskIds.add(id);
        }
        List<ExpectedBinding> bindings = parseBindings(arguments.get("runs"), taskIds);
        String ratio = AssistantArgs.optionalText(arguments, "isolationRatio", 12);
        if (ratio == null) {
            ratio = "30";
        }
        ratio = normalizeRatio(ratio);
        String focus = AssistantArgs.optionalText(arguments, "focus", 32);
        if (focus != null && !Set.of("subset", "purity", "sample_count").contains(focus)) {
            throw invalid("focus 只支持 subset、purity、sample_count");
        }

        List<RunEvidence> runs = new ArrayList<>();
        for (int index = 0; index < taskIds.size(); index++) {
            runs.add(readRun(taskIds.get(index), bindings.get(index)));
        }
        RunEvidence primary = runs.get(0);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("kind", "comparison");
        data.put("status", runs.stream().allMatch(run -> run.fresh) ? "ok" : "stale");
        data.put("ratio", ratio);
        data.put("focus", focus == null ? "subset" : focus);
        data.put("primaryTaskId", primary.taskId);
        data.put("baselineTaskIds", objectMapper.valueToTree(
                runs.stream().skip(1).map(run -> run.taskId).toList()));

        ArrayNode runArray = data.putArray("runs");
        for (RunEvidence run : runs) {
            runArray.add(run.toJson(objectMapper));
        }

        ArrayNode comparability = data.putArray("comparability");
        addComparability(comparability, runs, ratio);
        ArrayNode deltas = data.putArray("deltas");
        boolean allSameRatio = true;
        for (RunEvidence run : runs) {
            if (!run.hasRatio(ratio)) {
                allSameRatio = false;
            }
        }
        boolean allFresh = runs.stream().allMatch(run -> run.fresh);
        if (allSameRatio && allFresh) {
            for (RunEvidence baseline : runs.subList(1, runs.size())) {
                deltas.add(delta(primary, baseline, ratio));
            }
        }
        ArrayNode gaps = data.putArray("gaps");
        for (RunEvidence run : runs) {
            run.addGaps(gaps, ratio);
        }
        if (!allSameRatio) {
            gaps.addObject().put("code", "RATIO_METRIC_MISSING")
                    .put("message", "至少一份报告没有记录 " + ratio + "% 的完整指标，差值不可计算。");
        }
        if (!allFresh) {
            gaps.addObject().put("code", "RUN_BINDING_STALE")
                    .put("message", "所选运行已被新尝试覆盖或与绑定不一致，已保留原选择并停止计算差值；"
                            + "请从当前报告重新选择运行。");
        }
        if (deltas.isEmpty()) {
            gaps.addObject().put("code", "DELTA_UNAVAILABLE")
                    .put("message", "同比例指标或可比条件不齐，未给出差值。");
        }

        ArrayNode evidence = data.putArray("evidence");
        ArrayNode citations = data.putArray("citations");
        for (RunEvidence run : runs) {
            var evidenceNode = evidence.addObject().put("taskId", run.taskId)
                    .put("runId", run.runId)
                    .put("attempt", run.attempt)
                    .put("reportEndpoint", run.reportEndpoint)
                    .put("reportPage", "/tasks?taskId=" + run.taskId)
                    .put("value", run.describeRatio(ratio));
            if (run.expectedRunId != null) {
                evidenceNode.put("expectedRunId", run.expectedRunId);
            }
            if (run.expectedAttempt != null) {
                evidenceNode.put("expectedAttempt", run.expectedAttempt);
            }
            evidenceNode.put("bindingState", run.bindingState());
            citations.add(citation(run, ratio));
        }
        ArrayNode next = data.putArray("nextActions");
        next.addObject().put("action", "open_report").put("label", "打开报告核对原始字段")
                .put("path", "/tasks?taskId=" + primary.taskId);
        next.addObject().put("action", "open_export").put("label", "在任务详情页选择导出")
                .put("path", "/tasks?taskId=" + primary.taskId + "&tab=report");
        next.addObject().put("action", "ask_ratio").put("label", "换一个保留比例继续比较")
                .put("example", "只看 30%");
        if (!allFresh) {
            next.addObject().put("action", "choose_run").put("label", "重新选择当前可用运行");
        }

        String summary = allFresh
                ? "已读取 " + runs.size() + " 份有权访问的报告，按 " + ratio
                + "% 对齐；差值和证据缺口由服务端计算，未补值、未推断归因。"
                : "所选运行与当前报告不一致，已保留原选择并停止计算差值；"
                + "请重新选择当前可用运行。";
        return new AssistantToolResult(summary, data, null, null, Long.parseLong(primary.taskId),
                primary.runId, null);
    }

    private RunEvidence readRun(Long taskId, ExpectedBinding binding) {
        PreDefenseTask task = taskAccessGuard.requireReadable(taskId);
        Map<String, Object> report = reportService.getReport(taskId);
        Map<?, ?> cleaning = asMap(report.get("cleaningResult"));
        Map<?, ?> metrics = asMap(cleaning == null ? null : cleaning.get("metrics"));
        Map<?, ?> config = asMap(cleaning == null ? null : cleaning.get("effective_config"));
        Dataset dataset = (Dataset) report.get("dataset");
        String runId = text(cleaning == null ? null : cleaning.get("run_id"));
        Integer attempt = integer(cleaning == null ? null : cleaning.get("attempt"));
        String reportRunId = runId;
        String reportAttempt = attempt == null ? "unknown" : attempt.toString();
        Map<?, ?> snapshot = asMap(report.get("executionSnapshot"));
        String snapshotHash = text(snapshot == null ? null : snapshot.get("snapshotHash"));
        String dataVersionHint = datasetVersionHint(snapshot, runId, attempt);
        boolean reportMatchesCurrent = runId != null && runId.equals(task.getCurrentRunId())
                && attempt != null && attempt.equals(task.getCurrentAttempt());
        boolean bindingMismatch = binding != null && binding.isBound()
                && (!java.util.Objects.equals(runId, binding.runId())
                || !java.util.Objects.equals(attempt, binding.attempt()));
        boolean stale = !reportMatchesCurrent;
        return new RunEvidence(task.getTaskId().toString(), KnowledgeSanitizer.forModel(task.getTaskName(), 120),
                task.getStatus(), runId, attempt, reportRunId, reportAttempt, stale, bindingMismatch,
                binding == null ? null : binding.runId(), binding == null ? null : binding.attempt(),
                "GET /api/pre-defense/report/" + task.getTaskId(),
                dataset == null ? null : dataset.getDatasetName(),
                text(config == null ? null : config.get("dataset")), config,
                asRatioMap(metrics == null ? null : metrics.get("clean_purity_percent_by_isolation_ratio")),
                asRatioMap(metrics == null ? null : metrics.get("selected_sample_count_by_isolation_ratio")),
                snapshotHash, dataVersionHint);
    }

    private void addComparability(ArrayNode target, List<RunEvidence> runs, String ratio) {
        RunEvidence primary = runs.get(0);
        for (String field : COMPARED_CONFIG) {
            String mainValue = primary.configValue(field);
            boolean same = true;
            boolean known = mainValue != null;
            List<String> baselineValues = new ArrayList<>();
            for (RunEvidence baseline : runs.subList(1, runs.size())) {
                String value = baseline.configValue(field);
                baselineValues.add(value == null ? "未知" : value);
                known = known && value != null;
                same = same && value != null && value.equals(mainValue);
            }
            target.addObject().put("field", field)
                    .put("label", fieldLabel(field))
                    .put("state", known ? (same ? "same" : "different") : "unknown")
                    .put("primary", mainValue == null ? "未知" : mainValue)
                    .put("baseline", String.join(" / ", baselineValues))
                    .put("note", "same".equals(known ? (same ? "same" : "different") : "unknown")
                            ? "" : "此项不同或未知，不能把全部差异归因于单一变量。");
        }
        String dataState = primary.dataVersionHint == null ? "unknown" : "unknown";
        target.addObject().put("field", "data_version")
                .put("label", "数据版本")
                .put("state", dataState)
                .put("primary", primary.dataVersionHint == null ? "未知" : primary.dataVersionHint)
                .put("baseline", runs.stream().skip(1)
                        .map(run -> run.dataVersionHint == null ? "未知" : run.dataVersionHint)
                        .reduce((a, b) -> a + " / " + b).orElse("未知"))
                .put("note", "报告只记录了数据目录修改时间，没有内容哈希，不能判定为同一数据版本。");
        target.addObject().put("field", "ratio")
                .put("label", "保留比例")
                .put("state", "same")
                .put("primary", ratio + "%")
                .put("baseline", ratio + "%")
                .put("note", "纯度差按百分点，样本数差按个；两者不能互相替代。");
        for (RunEvidence baseline : runs.subList(1, runs.size())) {
            target.addObject().put("field", "attempt")
                    .put("label", "运行尝试")
                    .put("state", baseline.fresh ? "same" : "unknown")
                    .put("primary", primary.runId == null ? "未知" : primary.runId + " / #" + primary.attempt)
                    .put("baseline", baseline.runId == null ? "未知" : baseline.runId + " / #" + baseline.attempt)
                    .put("note", baseline.bindingMismatch
                            ? "对照报告与所选运行不一致，差值已停止计算。"
                            : baseline.stale
                            ? "对照报告不是任务当前尝试，旧尝试可能已被新运行覆盖。"
                            : "报告运行标识与任务当前尝试一致。");
        }
    }

    private List<ExpectedBinding> parseBindings(JsonNode runs, List<Long> taskIds) {
        List<ExpectedBinding> bindings = new ArrayList<>();
        if (runs == null) {
            taskIds.forEach(ignored -> bindings.add(new ExpectedBinding(null, null)));
            return bindings;
        }
        if (!runs.isArray() || runs.size() != taskIds.size()) {
            throw invalid("runs 的顺序和数量必须与 taskIds 一致");
        }
        for (int index = 0; index < runs.size(); index++) {
            JsonNode item = runs.get(index);
            if (!item.isObject()) {
                throw invalid("runs 的每一项必须是对象");
            }
            AssistantArgs.rejectUnknown(item, Set.of("taskId", "runId", "attempt"));
            String taskId = item.path("taskId").asText("");
            if (!taskId.equals(taskIds.get(index).toString())) {
                throw invalid("runs 的顺序必须与 taskIds 一致");
            }
            String runId = item.hasNonNull("runId") ? item.path("runId").asText() : null;
            if (runId != null && (runId.isBlank() || runId.length() > 64)) {
                throw invalid("runId 长度无效");
            }
            Integer attempt = null;
            if (item.has("attempt")) {
                if (!item.path("attempt").isIntegralNumber() || item.path("attempt").asInt() < 1) {
                    throw invalid("attempt 必须是正整数");
                }
                attempt = item.path("attempt").asInt();
            }
            bindings.add(new ExpectedBinding(runId, attempt));
        }
        return bindings;
    }

    private ObjectNode delta(RunEvidence primary, RunEvidence baseline, String ratio) {
        BigDecimal mainPurity = primary.purity(ratio);
        BigDecimal basePurity = baseline.purity(ratio);
        BigDecimal mainCount = primary.count(ratio);
        BigDecimal baseCount = baseline.count(ratio);
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("primaryTaskId", primary.taskId);
        delta.put("baselineTaskId", baseline.taskId);
        delta.put("ratio", ratio);
        if (mainPurity != null && basePurity != null) {
            delta.put("cleanPurityPercentagePoints", mainPurity.subtract(basePurity).stripTrailingZeros().toPlainString());
        } else {
            delta.putNull("cleanPurityPercentagePoints");
        }
        if (mainCount != null && baseCount != null) {
            delta.put("sampleCountDifference", mainCount.subtract(baseCount).stripTrailingZeros().toPlainString());
        } else {
            delta.putNull("sampleCountDifference");
        }
        delta.put("assertionBoundary", "差值只说明两次报告在该比例下的记录不同；配置或数据版本不齐时不能归因。");
        return delta;
    }

    private ObjectNode citation(RunEvidence run, String ratio) {
        ObjectNode citation = objectMapper.createObjectNode();
        citation.put("citationId", "report-" + run.taskId + "-" + ratio.replace('.', '_'));
        citation.put("title", "任务 #" + run.taskId + " 报告");
        citation.put("sourceType", "experiment_report");
        citation.put("sourceKey", run.reportEndpoint);
        citation.put("targetPath", "/tasks?taskId=" + run.taskId);
        citation.put("version", run.runId == null ? "unknown" : run.runId);
        if (run.attempt != null) {
            citation.put("attempt", run.attempt);
        }
        citation.put("sectionPath", "cleaningResult.metrics · " + ratio + "%");
        citation.put("startLine", 0);
        citation.put("endLine", 0);
        citation.put("snippet", run.describeRatio(ratio));
        citation.put("visibility", "owner");
        return citation;
    }

    private String datasetVersionHint(Map<?, ?> snapshot, String runId, Integer attempt) {
        if (snapshot == null) {
            return null;
        }
        Object attempts = snapshot.get("attempts");
        if (!(attempts instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            Map<?, ?> entry = asMap(item);
            if (entry == null || runId == null || !runId.equals(text(entry.get("runId")))
                    || attempt == null || !attempt.equals(integer(entry.get("attempt")))) {
                continue;
            }
            Map<?, ?> runtime = asMap(entry.get("runtime"));
            Map<?, ?> fingerprint = asMap(runtime == null ? null : runtime.get("datasetFingerprint"));
            Object modified = fingerprint == null ? null : fingerprint.get("lastModified");
            return modified == null ? null : "目录修改时间 " + modified + "（无内容哈希）";
        }
        return null;
    }

    private static String fieldLabel(String field) {
        return switch (field) {
            case "dataset" -> "数据集";
            case "seed" -> "随机种子";
            case "batch_size" -> "批大小";
            case "lr_poison" -> "投毒学习率";
            case "trigger_type" -> "触发器";
            case "poisoned_mode" -> "投毒模式";
            case "trigger_label" -> "目标标签";
            case "poisoned_portion" -> "投毒比例";
            case "num_epochs" -> "训练轮数";
            case "r" -> "保留比例参数 r";
            case "alpha1" -> "alpha1";
            case "alpha2" -> "alpha2";
            default -> field;
        };
    }

    private static String normalizeRatio(String raw) {
        String text = raw.replace("%", "").trim();
        if (!text.matches("[0-9]{1,3}(\\.[0-9]{1,2})?")) {
            throw invalid("保留比例格式无效");
        }
        BigDecimal value = new BigDecimal(text).stripTrailingZeros();
        if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw invalid("保留比例必须在 0 到 100 之间");
        }
        return value.toPlainString();
    }

    private static Map<String, BigDecimal> asRatioMap(Object raw) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        Map<?, ?> map = asMap(raw);
        if (map == null) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Number number)) {
                continue;
            }
            result.put(normalizeRatio(entry.getKey().toString()), new BigDecimal(number.toString()));
        }
        return result;
    }

    private static Map<?, ?> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? map : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static AssistantException invalid(String message) {
        return new AssistantException("ASSISTANT_TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST, message);
    }

    private record ExpectedBinding(String runId, Integer attempt) {
        boolean isBound() {
            return runId != null || attempt != null;
        }
    }

    private static final class RunEvidence {
        final String taskId;
        final String taskName;
        final String status;
        final String runId;
        final Integer attempt;
        final String reportRunId;
        final String reportAttempt;
        final boolean stale;
        final boolean bindingMismatch;
        final boolean fresh;
        final String expectedRunId;
        final Integer expectedAttempt;
        final String reportEndpoint;
        final String datasetName;
        final String configDataset;
        final Map<?, ?> config;
        final Map<String, BigDecimal> purity;
        final Map<String, BigDecimal> counts;
        final String snapshotHash;
        final String dataVersionHint;

        RunEvidence(String taskId, String taskName, String status, String runId, Integer attempt,
                    String reportRunId, String reportAttempt, boolean stale, boolean bindingMismatch,
                    String expectedRunId, Integer expectedAttempt, String reportEndpoint,
                    String datasetName, String configDataset, Map<?, ?> config,
                    Map<String, BigDecimal> purity, Map<String, BigDecimal> counts,
                    String snapshotHash, String dataVersionHint) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.status = status;
            this.runId = runId;
            this.attempt = attempt;
            this.reportRunId = reportRunId;
            this.reportAttempt = reportAttempt;
            this.stale = stale;
            this.bindingMismatch = bindingMismatch;
            this.fresh = !stale && !bindingMismatch;
            this.expectedRunId = expectedRunId;
            this.expectedAttempt = expectedAttempt;
            this.reportEndpoint = reportEndpoint;
            this.datasetName = datasetName;
            this.configDataset = configDataset;
            this.config = config;
            this.purity = purity;
            this.counts = counts;
            this.snapshotHash = snapshotHash;
            this.dataVersionHint = dataVersionHint;
        }

        String configValue(String field) {
            Object value = config == null ? null : config.get(field);
            return value == null ? null : String.valueOf(value);
        }

        BigDecimal purity(String ratio) {
            return purity.get(ratio);
        }

        BigDecimal count(String ratio) {
            return counts.get(ratio);
        }

        boolean hasRatio(String ratio) {
            return purity.containsKey(ratio) && counts.containsKey(ratio);
        }

        String bindingState() {
            if (bindingMismatch) {
                return "mismatch";
            }
            if (stale) {
                return "stale";
            }
            return expectedRunId == null && expectedAttempt == null ? "unbound" : "bound";
        }

        String describeRatio(String ratio) {
            String purityText = purity.containsKey(ratio) ? purity.get(ratio).stripTrailingZeros().toPlainString() + "%" : "未采集";
            String countText = counts.containsKey(ratio) ? counts.get(ratio).stripTrailingZeros().toPlainString() + " 个" : "未采集";
            return ratio + "% · 清洗纯度 " + purityText + " · 保留样本 " + countText;
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("taskId", taskId);
            node.put("taskName", taskName);
            node.put("status", status);
            node.put("runId", runId);
            node.put("attempt", attempt);
            node.put("reportRunId", reportRunId);
            node.put("reportAttempt", reportAttempt);
            node.put("staleAttempt", stale);
            node.put("bindingMismatch", bindingMismatch);
            node.put("bindingState", bindingState());
            if (expectedRunId != null) {
                node.put("expectedRunId", expectedRunId);
            }
            if (expectedAttempt != null) {
                node.put("expectedAttempt", expectedAttempt);
            }
            node.put("datasetName", datasetName);
            node.put("configDataset", configDataset);
            node.put("snapshotHash", snapshotHash);
            node.put("datasetVersionState", "unknown");
            node.put("datasetVersionHint", dataVersionHint);
            node.put("reportEndpoint", reportEndpoint);
            ObjectNode purityNode = node.putObject("cleanPurityPercentByIsolationRatio");
            purity.forEach((key, value) -> purityNode.put(key, value));
            ObjectNode countNode = node.putObject("selectedSampleCountByIsolationRatio");
            counts.forEach((key, value) -> countNode.put(key, value));
            return node;
        }

        void addGaps(ArrayNode gaps, String ratio) {
            if (bindingMismatch) {
                gaps.addObject().put("code", "RUN_BINDING_MISMATCH").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 的报告运行 " + reportRunId + " / #"
                                + reportAttempt + " 与所选运行 " + expectedRunId + " / #"
                                + expectedAttempt + " 不一致；已停止计算该组差值。");
            } else if (stale) {
                gaps.addObject().put("code", "STALE_ATTEMPT").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 的报告运行标识不是当前尝试，比较前需重新读取该运行。");
            }
            if (!purity.containsKey(ratio)) {
                gaps.addObject().put("code", "PURITY_NOT_COLLECTED").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 的报告没有记录 " + ratio + "% 的清洗纯度。");
            }
            if (!counts.containsKey(ratio)) {
                gaps.addObject().put("code", "SAMPLE_COUNT_NOT_COLLECTED").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 的报告没有记录 " + ratio + "% 的保留样本数。");
            }
            if (config == null) {
                gaps.addObject().put("code", "CONFIG_UNKNOWN").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 的报告缺少有效配置，条件无法逐项对齐。");
            }
            if (dataVersionHint == null) {
                gaps.addObject().put("code", "DATA_VERSION_UNKNOWN").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 未记录可核对的数据版本，不能排除数据变化。");
            } else {
                gaps.addObject().put("code", "DATA_VERSION_CONTENT_UNKNOWN").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 只记录了数据目录修改时间，没有内容哈希，不能判定为同一数据版本。");
            }
            if (!"success".equals(status)) {
                gaps.addObject().put("code", "RUN_NOT_SUCCESS").put("taskId", taskId)
                        .put("message", "任务 #" + taskId + " 当前状态为 " + status + "，报告可能不完整。");
            }
        }
    }
}
