package me.zhengjie.modules.system.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.DuringDefenseMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.executor.DefenseTaskExecutor;
import me.zhengjie.modules.system.service.executor.PythonProcessWaiter;
import me.zhengjie.modules.system.service.executor.dto.DefenseTaskRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Local process executor reserved for later Python security algorithms.
 */
@Slf4j
@Service
@Profile("python-executor")
@RequiredArgsConstructor
public class PythonDefenseTaskExecutor implements DefenseTaskExecutor {

    private final PreDefenseMapper preDefenseMapper;
    private final DuringDefenseMapper duringDefenseMapper;
    private final PythonProcessWaiter processWaiter = new PythonProcessWaiter();
    private final ConcurrentMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final java.util.Set<String> canceledRuns = ConcurrentHashMap.newKeySet();

    @Value("${security-model.executor.python.executable:python}")
    private String pythonExecutable;

    @Value("${security-model.executor.python.script:}")
    private String pythonScript;

    @Value("${security-model.executor.python.workspace:${java.io.tmpdir}/security-model-executor}")
    private String executorWorkspace;

    @Value("${security-model.executor.python.timeout-seconds:3600}")
    private long timeoutSeconds;

    @Override
    @Async("taskAsync")
    public void runPreDefense(DefenseTaskRequest request) {
        runPythonTask(request, "pre_defense");
    }

    @Override
    @Async("taskAsync")
    public void runDuringDefense(DefenseTaskRequest request) {
        runPythonTask(request, "during_defense");
    }

    private void runPythonTask(DefenseTaskRequest request, String stage) {
        String startedAt = LocalDateTime.now().toString();
        Long taskId = request.getTaskId();
        ExecutionContext context = null;
        try {
            if (isCanceled(request)) {
                log.info("Skip canceled Python {} task {}, runId={}", stage, taskId, request.getRunId());
                return;
            }
            context = createExecutionContext(request, stage);
            updateStatus(stage, request, "running", 10);
            String resultJson = invokePython(request, context, startedAt);
            String resultStatus = "success";
            if ("pre_defense".equals(stage)) {
                resultStatus = validatePreDefenseResult(resultJson, request);
            }
            persistResult(stage, request, resultJson);
            finishTask(stage, request, resultStatus, "success".equals(resultStatus) ? 100 : 0);
            log.info("Python {} task {} completed with status {}", stage, taskId, resultStatus);
        } catch (TaskCanceledException e) {
            log.info("Python {} task {} canceled, runId={}", stage, taskId, request.getRunId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (isCanceled(request)) {
                log.info("Python {} task {} canceled while interrupted, runId={}",
                        stage, taskId, request.getRunId());
                return;
            }
            persistResult(stage, request, buildFailureResult(stage, startedAt,
                    request, context, "TASK_INTERRUPTED", "Task was interrupted"));
            finishTask(stage, request, "failed", 0);
            log.warn("Python {} task {} interrupted", stage, taskId);
        } catch (Exception e) {
            if (isCanceled(request)) {
                log.info("Ignore Python {} failure after cancellation, task={}, runId={}",
                        stage, taskId, request.getRunId());
                return;
            }
            String errorCode = classifyFailure(e);
            persistResult(stage, request, buildFailureResult(stage, startedAt,
                    request, context, errorCode, stripErrorCode(e.getMessage())));
            finishTask(stage, request, "failed", 0);
            log.error("Python {} task {} failed with {}", stage, taskId, errorCode, e);
        } finally {
            if (request.getRunId() != null) {
                canceledRuns.remove(request.getRunId());
            }
        }
    }

    private ExecutionContext createExecutionContext(DefenseTaskRequest request, String stage)
            throws IOException {
        String attemptName = request.getAttempt() == null
                ? "attempt-" + request.getRunId()
                : "attempt-" + request.getAttempt();
        Path taskDir = Paths.get(executorWorkspace, stage + "-" + request.getTaskId(), attemptName);
        Files.createDirectories(taskDir);
        return new ExecutionContext(
                taskDir,
                taskDir.resolve("request.json"),
                taskDir.resolve("result.json"),
                taskDir.resolve("stdout.log"),
                taskDir.resolve("stderr.log"));
    }

    private String invokePython(DefenseTaskRequest request, ExecutionContext context, String startedAt)
            throws IOException, InterruptedException, TaskCanceledException {
        if (!StringUtils.hasText(pythonScript)) {
            throw new IllegalStateException(
                    "EXECUTOR_NOT_CONFIGURED: security-model.executor.python.script is not configured");
        }

        Files.write(context.requestFile, JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8));

        List<String> command = Arrays.asList(
                pythonExecutable,
                pythonScript,
                "--stage", request.getStage(),
                "--request", context.requestFile.toAbsolutePath().toString(),
                "--result", context.resultFile.toAbsolutePath().toString()
        );

        Files.createDirectories(Paths.get(executorWorkspace));
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(executorWorkspace));
        processBuilder.redirectOutput(context.stdoutFile.toFile());
        processBuilder.redirectError(context.stderrFile.toFile());

        Process process = processBuilder.start();
        activeProcesses.put(request.getRunId(), process);
        try {
            if (isCanceled(request)) {
                process.destroyForcibly();
                throw new TaskCanceledException();
            }

            PythonProcessWaiter.Outcome outcome =
                    processWaiter.waitFor(process, timeoutSeconds, () -> isCanceled(request));
            if (outcome == PythonProcessWaiter.Outcome.CANCELED) {
                throw new TaskCanceledException();
            }
            if (outcome == PythonProcessWaiter.Outcome.TIMED_OUT) {
                throw new IllegalStateException("TASK_TIMEOUT: Python executor timed out after "
                        + timeoutSeconds + " seconds; process was forcibly terminated");
            }

            String resultJson = readFileIfExists(context.resultFile);
            if (process.exitValue() != 0) {
                if (hasFailedResult(resultJson)) {
                    updateStatus(request.getStage(), request, "running", 90);
                    return resultJson;
                }
                throw new IllegalStateException("PYTHON_PROCESS_EXITED: Python executor exited with code "
                        + process.exitValue()
                        + ", stderr: " + readFileIfExists(context.stderrFile));
            }

            if (!StringUtils.hasText(resultJson)) {
                throw new IllegalStateException(
                        "RESULT_MISSING: Python executor completed without result.json");
            }
            updateStatus(request.getStage(), request, "running", 90);
            return resultJson;
        } finally {
            activeProcesses.remove(request.getRunId(), process);
        }
    }

    private String validatePreDefenseResult(String resultJson, DefenseTaskRequest request) {
        if (!StringUtils.hasText(resultJson)) {
            throw new IllegalStateException("RESULT_MISSING: result.json is empty");
        }
        JSONObject result;
        try {
            result = JSON.parseObject(resultJson);
        } catch (Exception e) {
            throw new IllegalStateException("RESULT_INVALID_JSON: result.json is not a JSON object", e);
        }
        if (result == null) {
            throw new IllegalStateException("RESULT_INVALID_JSON: result.json is not a JSON object");
        }
        requireResultText(result, "executor");
        if (!"pre_defense".equals(result.getString("stage"))) {
            throw new IllegalStateException("RESULT_PROTOCOL_ERROR: stage does not match pre_defense");
        }
        requireResultText(result, "message");
        requireResultText(result, "started_at");
        requireResultText(result, "finished_at");
        if (result.get("artifacts") == null || !(result.get("artifacts") instanceof JSONObject)) {
            throw new IllegalStateException("RESULT_PROTOCOL_ERROR: artifacts must be a JSON object");
        }
        if (!request.getRunId().equals(result.getString("run_id"))) {
            throw new IllegalStateException("RESULT_PROTOCOL_ERROR: run_id does not match request");
        }
        Integer resultAttempt = result.getInteger("attempt");
        if (request.getAttempt() == null || resultAttempt == null
                || !request.getAttempt().equals(resultAttempt)) {
            throw new IllegalStateException("RESULT_PROTOCOL_ERROR: attempt does not match request");
        }
        String status = result.getString("status");
        if (!"success".equals(status) && !"failed".equals(status)) {
            throw new IllegalStateException("RESULT_PROTOCOL_ERROR: status must be success or failed");
        }
        if ("failed".equals(status)
                && (!StringUtils.hasText(result.getString("error_code"))
                || !StringUtils.hasText(result.getString("failure_reason")))) {
            throw new IllegalStateException(
                    "RESULT_PROTOCOL_ERROR: failed result requires error_code and failure_reason");
        }
        return status;
    }

    private void requireResultText(JSONObject result, String key) {
        if (!StringUtils.hasText(result.getString(key))) {
            throw new IllegalStateException("RESULT_PROTOCOL_ERROR: " + key + " is required");
        }
    }

    private String classifyFailure(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message != null) {
            int separator = message.indexOf(':');
            if (separator > 0) {
                String candidate = message.substring(0, separator).trim();
                if (isKnownErrorCode(candidate)) {
                    return candidate;
                }
            }
        }
        return "PYTHON_EXECUTION_ERROR";
    }

    private String stripErrorCode(String message) {
        if (!StringUtils.hasText(message)) {
            return "Unknown execution error";
        }
        int separator = message.indexOf(':');
        if (separator > 0 && isKnownErrorCode(message.substring(0, separator).trim())) {
            return message.substring(separator + 1).trim();
        }
        return message;
    }

    private boolean isKnownErrorCode(String value) {
        return "EXECUTOR_NOT_CONFIGURED".equals(value)
                || "TASK_TIMEOUT".equals(value)
                || "PYTHON_PROCESS_EXITED".equals(value)
                || "RESULT_MISSING".equals(value)
                || "RESULT_INVALID_JSON".equals(value)
                || "RESULT_PROTOCOL_ERROR".equals(value);
    }

    private boolean hasFailedResult(String resultJson) {
        if (!StringUtils.hasText(resultJson)) {
            return false;
        }
        try {
            JSONObject result = JSON.parseObject(resultJson);
            return result != null && "failed".equals(result.getString("status"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isCanceled(DefenseTaskRequest request) {
        return request.getRunId() != null && canceledRuns.contains(request.getRunId());
    }

    @Override
    public boolean cancelPreDefense(Long taskId, String runId) {
        if (runId == null) {
            return false;
        }
        canceledRuns.add(runId);
        Process process = activeProcesses.get(runId);
        if (process == null) {
            return false;
        }
        process.destroyForcibly();
        log.info("Destroyed Python pre-defense process, taskId={}, runId={}", taskId, runId);
        return true;
    }

    private String buildFailureResult(String stage, String startedAt, DefenseTaskRequest request,
                                      ExecutionContext context, String errorCode, String failureReason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "python-process");
        result.put("stage", stage);
        result.put("status", "failed");
        result.put("message", "python executor failed");
        result.put("run_id", request.getRunId());
        result.put("attempt", request.getAttempt());
        result.put("error_code", errorCode);
        result.put("failure_reason", StringUtils.hasText(failureReason) ? failureReason : "Unknown execution error");
        result.put("started_at", startedAt);
        result.put("finished_at", LocalDateTime.now().toString());
        result.put("artifacts", context == null
                ? new LinkedHashMap<String, Object>()
                : buildArtifacts(context));
        return JSON.toJSONString(result);
    }

    private Map<String, Object> buildArtifacts(ExecutionContext context) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("type", "executor_workspace");
        artifacts.put("available", context != null);
        if (context != null) {
            artifacts.put("path", context.taskDir.toAbsolutePath().toString());
            artifacts.put("logs", Arrays.asList(
                    context.stdoutFile.toAbsolutePath().toString(),
                    context.stderrFile.toAbsolutePath().toString()));
        }
        artifacts.put("message", "Python executor workspace");
        return artifacts;
    }

    private String readFileIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private void persistResult(String stage, DefenseTaskRequest request, String resultJson) {
        if ("pre_defense".equals(stage)) {
            int updated = preDefenseMapper.updateResultForRun(
                    request.getTaskId(), request.getRunId(), null, resultJson);
            if (updated == 0) {
                log.warn("Ignored stale pre-defense result callback, task={}, runId={}",
                        request.getTaskId(), request.getRunId());
            }
            return;
        }
        duringDefenseMapper.updateTrainingResult(request.getTaskId(), null, resultJson);
    }

    private void updateStatus(String stage, DefenseTaskRequest request, String status, Integer progress) {
        if ("pre_defense".equals(stage)) {
            int updated = preDefenseMapper.updateRunningProgress(
                    request.getTaskId(), request.getRunId(), progress);
            if (updated == 0) {
                log.warn("Ignored stale pre-defense progress callback, task={}, runId={}, progress={}",
                        request.getTaskId(), request.getRunId(), progress);
            }
            return;
        }
        duringDefenseMapper.updateStatus(request.getTaskId(), status, progress);
    }

    private void finishTask(String stage, DefenseTaskRequest request, String status, Integer progress) {
        if ("pre_defense".equals(stage)) {
            int updated = preDefenseMapper.finishForRun(
                    request.getTaskId(), request.getRunId(), status, progress, LocalDateTime.now());
            if (updated == 0) {
                log.warn("Ignored stale pre-defense finish callback, task={}, runId={}, status={}",
                        request.getTaskId(), request.getRunId(), status);
            }
            return;
        }

        DuringDefense task = duringDefenseMapper.selectById(request.getTaskId());
        if (task == null) {
            return;
        }
        task.setStatus(status);
        task.setProgress(progress);
        task.setEndTime(LocalDateTime.now());
        duringDefenseMapper.updateById(task);
    }

    private static class ExecutionContext {
        private final Path taskDir;
        private final Path requestFile;
        private final Path resultFile;
        private final Path stdoutFile;
        private final Path stderrFile;

        private ExecutionContext(Path taskDir, Path requestFile, Path resultFile,
                                 Path stdoutFile, Path stderrFile) {
            this.taskDir = taskDir;
            this.requestFile = requestFile;
            this.resultFile = resultFile;
            this.stdoutFile = stdoutFile;
            this.stderrFile = stderrFile;
        }
    }

    private static class TaskCanceledException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
