package com.securitymodel.application.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.report.PreDefenseLogQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.application.task.TaskFailureMessages;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.persistence.mapper.TaskDispatchIntentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Diagnoses one failed attempt using persisted facts and a fixed set of checks. */
@Service
public class TaskFailureDiagnosis {

    private static final Set<String> INTENT_STATES = Set.of(TaskDispatchIntent.PENDING,
            TaskDispatchIntent.SUBMITTING, TaskDispatchIntent.SUBMITTED, TaskDispatchIntent.ABANDONED);
    private final TaskAccessGuard tasks;
    private final TaskDispatchIntentMapper intents;
    private final ObjectMapper json;
    private final PreDefenseLogQueryService logs;

    public TaskFailureDiagnosis(TaskAccessGuard tasks, TaskDispatchIntentMapper intents, ObjectMapper json) {
        this(tasks, intents, json, null);
    }

    @Autowired
    public TaskFailureDiagnosis(TaskAccessGuard tasks, TaskDispatchIntentMapper intents, ObjectMapper json,
                                PreDefenseLogQueryService logs) {
        this.tasks = tasks;
        this.intents = intents;
        this.json = json;
        this.logs = logs;
    }

    public Diagnosis diagnose(Long taskId, String runId, Integer attempt) {
        PreDefenseTask task = tasks.requireOwned(taskId);
        requireFailedAttempt(task, runId, attempt);
        boolean hasRun = runId != null && attempt != null;
        TaskDispatchIntent intent = hasRun ? intents.findByRun(taskId, runId, attempt) : null;
        String reportCode = hasRun ? reportCode(task.getCleaningResult(), runId, attempt) : null;
        String dispatchCode = intent == null ? null : stableCode(intent.getErrorCode());
        requireFailedAttempt(tasks.requireOwned(taskId), runId, attempt);

        List<String> facts = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        facts.add("任务状态为失败。");
        if (!hasRun) {
            gaps.add("缺少完整运行标识，无法归属报告或派发记录。");
        } else if (intent == null) {
            gaps.add("没有找到本次运行的派发记录。");
        } else if (intent.getStatus() != null && INTENT_STATES.contains(intent.getStatus())) {
            facts.add("本次运行的派发记录状态为 " + intent.getStatus() + "。");
            if (intent.getSubmittedAt() != null || TaskDispatchIntent.SUBMITTED.equals(intent.getStatus())) {
                gaps.add("派发已被执行器接受，仍需运行日志确认子进程是否实际启动。");
            }
        }
        if (reportCode == null) {
            gaps.add("本次运行缺少可核对的报告错误码。");
        } else {
            facts.add("报告错误码 " + reportCode + "，" + TaskFailureMessages.forCode(reportCode) + "。");
        }
        if (dispatchCode != null && !dispatchCode.equals(reportCode)) {
            facts.add("派发错误码 " + dispatchCode + "。");
        }
        boolean conflictingCodes = reportCode != null && dispatchCode != null && !reportCode.equals(dispatchCode);
        if (conflictingCodes) {
            gaps.add("报告与派发记录中的错误码不同，需核对两条记录对应的失败事件。");
        }
        String logStage = addLogSignals(taskId, facts, gaps, conflictingCodes);
        Check check = conflictingCodes ? null : checkFor(reportCode == null ? dispatchCode : reportCode);
        if (logStage != null && (check == null || "阶段待确认".equals(check.stage()))) {
            check = new Check(logStage, stageAdvice(logStage));
        }
        if (check == null) {
            check = new Check("阶段待确认",
                    "先查看本次尝试的报告和日志；如信息缺失，请管理员核对派发及执行记录。");
        }
        if ("阶段待确认".equals(check.stage())) {
            gaps.add("现有证据不足以确定失败阶段或根因。");
        }
        return new Diagnosis(taskId.toString(), attempt, check.stage(), facts, gaps, check.next());
    }

    /**
     * Reads only this attempt's already-authorized log projection. Raw lines never leave this method;
     * only a coarse signal and a check suggestion are returned.
     */
    private String addLogSignals(Long taskId, List<String> facts, List<String> gaps, boolean conflictingCodes) {
        if (logs == null || conflictingCodes) {
            return null;
        }
        try {
            Map<String, Object> page = logs.getLogs(taskId, Map.of());
            if (!Boolean.TRUE.equals(page.get("available")) || !(page.get("logs") instanceof List<?> items)) {
                gaps.add("本次尝试没有可读取的执行日志，无法核对日志信号。");
                return null;
            }
            String all = items.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(item -> String.valueOf(item.get("content")))
                    .reduce("", (a, b) -> a + "\n" + b);
            if (all.matches("(?is).*(UnicodeEncodeError|UnicodeDecodeError|charmap|codec can't encode|"
                    + "'gbk' codec|invalid continuation byte).*")) {
                facts.add("本次尝试的日志出现编码异常信号（未展示原始行）。");
                return "执行阶段（编码故障）";
            }
            if (all.matches("(?is).*(FileNotFoundError|No such file or directory|DATASET_UNAVAILABLE|"
                    + "CONFIG_NOT_FOUND|缺少配置).*")) {
                facts.add("本次尝试的日志出现文件或配置缺失信号（未展示原始行）。");
                return "执行准备（配置或输入缺失）";
            }
            if (all.matches("(?is).*(CUDA out of memory|OutOfMemoryError|GPU OOM).*")) {
                facts.add("本次尝试的日志出现显存不足信号（未展示原始行）。");
                return "执行阶段（显存不足）";
            }
            facts.add("本次尝试的日志可读取，但未匹配到已登记的故障信号。");
            return null;
        } catch (RuntimeException exception) {
            gaps.add("本次尝试的日志读取失败，无法核对日志信号。");
            return null;
        }
    }

    private static String stageAdvice(String stage) {
        if (stage.contains("编码")) {
            return "核对本次尝试日志中的文件名、编码参数和运行账号区域设置；修复后用同一数据清单重试。";
        }
        if (stage.contains("配置")) {
            return "核对数据集路径、执行器配置和运行账号读取权限；补齐后再提交重试。";
        }
        if (stage.contains("显存")) {
            return "核对失败时的 GPU 租约和批大小；确认显存释放后再重试。";
        }
        return "查看本次尝试的日志和报告，确认失败位置后再决定是否重试。";
    }

    private void requireFailedAttempt(PreDefenseTask task, String runId, Integer attempt) {
        if (!Objects.equals(runId, task.getCurrentRunId()) || !Objects.equals(attempt, task.getCurrentAttempt())
                || !"failed".equals(task.getStatus())) {
            throw new AssistantException("ASSISTANT_DIAGNOSIS_STALE", HttpStatus.CONFLICT,
                    "任务状态或运行尝试已变化，请返回任务详情重新选择。");
        }
    }

    private String reportCode(String report, String runId, Integer attempt) {
        if (report == null || report.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(report);
            if (root != null && runId.equals(root.path("run_id").textValue())
                    && root.path("attempt").isIntegralNumber()
                    && root.path("attempt").canConvertToInt() && root.path("attempt").intValue() == attempt) {
                return stableCode(root.path("error_code").textValue());
            }
        } catch (JsonProcessingException ignored) {
            // An unreadable report is an evidence gap.
        }
        return null;
    }

    private String stableCode(String code) {
        return checkFor(code) != null ? code : null;
    }

    private Check checkFor(String code) {
        return switch (code == null ? "" : code) {
            case "EXECUTOR_NOT_CONFIGURED", "PROCESS_RECORD_FAILED" -> new Check("执行准备",
                    "请管理员检查实际执行进程的配置、运行账号和工作目录写权限。");
            case "RABBIT_UNROUTABLE", "RABBIT_PUBLISH_FAILED", "DISPATCH_PUBLISH_FAILED",
                    "DISPATCH_IDENTITY_MISMATCH", "DISPATCH_REPLAY_FAILED", "DISPATCH_INTENT_MISSING",
                    "DISPATCH_RESOURCE_INVALID", "DISPATCH_RESOURCE_DISABLED", "TASK_DISPATCH_FAILED" ->
                    new Check("任务派发", "请管理员核对当前运行的派发记录、资源状态及 Worker 消费状态。");
            case "GPU_BUSY", "GPU_LOCK_BUSY", "GPU_LEASE_LOST", "GPU_LEASE_TABLE_UNAVAILABLE" -> new Check("资源协调",
                    "核对失败时的 GPU 租约、槽位状态及其他运行任务。");
            case "GPU_OOM", "CUDA_UNAVAILABLE", "TASK_TIMEOUT" ->
                    new Check("执行阶段", "查看本次运行的日志和失败时的设备状态，定位错误出现的位置。");
            case "PYTHON_PROCESS_EXITED" -> new Check("阶段待确认",
                    "查看本次尝试日志中的首个异常和退出码，确认是编码、配置还是设备问题。");
            case "RESULT_MISSING", "RESULT_INVALID_JSON", "RESULT_PROTOCOL_ERROR" ->
                    new Check("结果校验", "核对当前运行的日志与产物状态，并请管理员检查结果协议。");
            case "EXECUTOR_RESTARTED", "TASK_INTERRUPTED", "DISPATCH_UNCERTAIN", "DISPATCH_UNCERTAIN_RECOVERY",
                    "PYTHON_EXECUTION_ERROR" -> new Check("阶段待确认",
                    "请管理员对照当前运行的日志、派发和进程清理记录，确认中断发生的位置。");
            default -> null;
        };
    }

    private record Check(String stage, String next) { }

    public record Diagnosis(String taskId, Integer attempt, String stage, List<String> facts,
                            List<String> gaps, String nextCheck) {
        public String answer() {
            return "失败阶段：" + stage + "\n已确认：" + String.join("", facts)
                    + (gaps.isEmpty() ? "" : "\n证据缺口：" + String.join("", gaps))
                    + "\n下一步检查：" + nextCheck;
        }
    }
}
