package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.task.DefenseTaskRequest;
import com.securitymodel.application.task.ExecutionFingerprintService;
import com.securitymodel.application.task.PythonProcessWaiter;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PythonLddTaskExecutor implements DefenseTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonLddTaskExecutor.class);

    private final PreDefenseTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final ExecutionFingerprintService fingerprintService;
    private final com.securitymodel.application.task.GpuLeaseService gpuLeaseService;
    private final ExecutorArtifactPolicy artifactPolicy;
    private final AttemptProcessRegistry processRegistry;
    private final String pythonExecutable;
    private final String pythonScript;
    private final long timeoutSeconds;
    private final ConcurrentMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final java.util.Set<String> canceledRuns = ConcurrentHashMap.newKeySet();

    public PythonLddTaskExecutor(
            PreDefenseTaskMapper taskMapper,
            ObjectMapper objectMapper,
            ExecutionFingerprintService fingerprintService,
            com.securitymodel.application.task.GpuLeaseService gpuLeaseService,
            ExecutorArtifactPolicy artifactPolicy,
            AttemptProcessRegistry processRegistry,
            @Value("${security-model.executor.python.executable:python}") String pythonExecutable,
            @Value("${security-model.executor.python.script:}") String pythonScript,
            @Value("${security-model.executor.python.timeout-seconds:3600}") long timeoutSeconds) {
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.fingerprintService = fingerprintService;
        this.gpuLeaseService = gpuLeaseService;
        this.artifactPolicy = artifactPolicy;
        this.processRegistry = processRegistry;
        this.pythonExecutable = pythonExecutable;
        this.pythonScript = pythonScript;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    @Async("taskAsync")
    public void runPreDefense(DefenseTaskRequest request) {
        String startedAt = OffsetDateTime.now().toString();
        ExecutionContext context = null;
        try {
            if (isCanceled(request)) {
                return;
            }
            context = createContext(request);
            updateProgress(request, 10);
            String result = invoke(request, context);
            String status = validateResult(result, request);
            persistResult(request, result);
            finish(request, status, "success".equals(status) ? 100 : 0);
        } catch (CanceledException exception) {
            log.info("LDD task canceled: taskId={}, runId={}", request.getTaskId(), request.getRunId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!isCanceled(request)) {
                fail(request, context, startedAt, "TASK_INTERRUPTED", "LDD 执行线程被中断");
            }
        } catch (Exception exception) {
            if (!isCanceled(request)) {
                fail(request, context, startedAt, classify(exception), stripCode(exception.getMessage()));
                log.error("LDD task failed: taskId={}, runId={}", request.getTaskId(), request.getRunId(), exception);
            }
        } finally {
            canceledRuns.remove(request.getRunId());
            if (context != null) {
                artifactPolicy.finalizeAttempt(context.taskDir());
            }
            // The lease is held for the whole process lifetime, including cancel unwinding.
            gpuLeaseService.releaseQuietly(request.getGpuLeaseToken());
        }
    }

    @Override
    public boolean cancelPreDefense(Long taskId, String runId) {
        if (!StringUtils.hasText(runId)) {
            return false;
        }
        canceledRuns.add(runId);
        Process process = activeProcesses.get(runId);
        if (process == null) {
            return false;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        return true;
    }

    private ExecutionContext createContext(DefenseTaskRequest request) throws IOException {
        Path taskDir = Paths.get(fingerprintService.getWorkspace(), "pre_defense-" + request.getTaskId(),
                "attempt-" + request.getAttempt()).toAbsolutePath().normalize();
        Files.createDirectories(taskDir);
        return new ExecutionContext(taskDir, taskDir.resolve("request.json"), taskDir.resolve("result.json"),
                taskDir.resolve("stdout.log"), taskDir.resolve("stderr.log"),
                taskDir.resolve("progress.json"));
    }

    private String invoke(DefenseTaskRequest request, ExecutionContext context)
            throws IOException, InterruptedException, CanceledException {
        if (!StringUtils.hasText(pythonScript)) {
            throw new IllegalStateException("EXECUTOR_NOT_CONFIGURED: 未配置 SECURITY_MODEL_PYTHON_SCRIPT");
        }
        Files.writeString(context.requestFile, objectMapper.writeValueAsString(request), StandardCharsets.UTF_8);
        List<String> command = List.of(pythonExecutable, pythonScript,
                "--stage", request.getStage(),
                "--request", context.requestFile.toString(),
                "--result", context.resultFile.toString(),
                "--progress-file", context.progressFile.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(context.stdoutFile.toFile())
                .redirectError(context.stderrFile.toFile());
        builder.directory(Paths.get(fingerprintService.getWorkspace()).toFile());
        Process process = builder.start();
        processRegistry.record(context.taskDir, process);
        activeProcesses.put(request.getRunId(), process);
        try {
            PythonProcessWaiter.Outcome outcome = new PythonProcessWaiter()
                    .waitFor(process, timeoutSeconds, () -> isCanceled(request),
                            context.progressFile, path -> updateProgressFromSnapshot(request, path));
            if (outcome == PythonProcessWaiter.Outcome.CANCELED) {
                throw new CanceledException();
            }
            if (outcome == PythonProcessWaiter.Outcome.TIMED_OUT) {
                throw new IllegalStateException("TASK_TIMEOUT: LDD 执行超过 " + timeoutSeconds + " 秒");
            }
            String result = readIfExists(context.resultFile);
            if (process.exitValue() != 0 && !isFailedResult(result)) {
                String stderr = readIfExists(context.stderrFile);
                throw new IllegalStateException(classifyProcessFailure(stderr)
                        + ": Python 进程退出码 " + process.exitValue() + "，stderr: " + stderr);
            }
            updateProgress(request, 90);
            if (!StringUtils.hasText(result)) {
                throw new IllegalStateException("RESULT_MISSING: 未生成 result.json");
            }
            return result;
        } finally {
            activeProcesses.remove(request.getRunId(), process);
        }
    }

    private String validateResult(String raw, DefenseTaskRequest request) {
        try {
            JsonNode result = objectMapper.readTree(raw);
            requireText(result, "executor");
            requireText(result, "stage");
            requireText(result, "message");
            requireText(result, "started_at");
            requireText(result, "finished_at");
            if (!"pre_defense".equals(result.get("stage").asText())) {
                throw new IllegalStateException("RESULT_PROTOCOL_ERROR: stage 不匹配");
            }
            if (!request.getRunId().equals(text(result, "run_id"))
                    || !request.getAttempt().equals(result.path("attempt").asInt(-1))) {
                throw new IllegalStateException("RESULT_PROTOCOL_ERROR: run_id 或 attempt 不匹配");
            }
            String status = text(result, "status");
            if (!"success".equals(status) && !"failed".equals(status)) {
                throw new IllegalStateException("RESULT_PROTOCOL_ERROR: status 必须为 success 或 failed");
            }
            if (!result.has("artifacts") || !result.get("artifacts").isObject()) {
                throw new IllegalStateException("RESULT_PROTOCOL_ERROR: artifacts 必须为 JSON 对象");
            }
            if ("failed".equals(status)
                    && (!StringUtils.hasText(text(result, "error_code"))
                    || !StringUtils.hasText(text(result, "failure_reason")))) {
                throw new IllegalStateException("RESULT_PROTOCOL_ERROR: failed 结果缺少错误信息");
            }
            return status;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("RESULT_INVALID_JSON: result.json 不是合法 JSON", exception);
        }
    }

    private void persistResult(DefenseTaskRequest request, String result) {
        if (taskMapper.updateResultForRun(request.getTaskId(), request.getRunId(), null, result) == 0) {
            log.warn("Ignore stale LDD result: taskId={}, runId={}", request.getTaskId(), request.getRunId());
        }
    }

    private void updateProgress(DefenseTaskRequest request, int progress) {
        taskMapper.updateRunningProgress(request.getTaskId(), request.getRunId(), progress);
    }

    private void updateProgressFromSnapshot(DefenseTaskRequest request, Path progressFile) {
        try {
            JsonNode snapshot = objectMapper.readTree(Files.readString(progressFile, StandardCharsets.UTF_8));
            String runId = text(snapshot, "run_id");
            int attempt = snapshot.path("attempt").asInt(-1);
            if (runId != null && !runId.equals(request.getRunId())) {
                return;
            }
            if (attempt >= 0 && !request.getAttempt().equals(attempt)) {
                return;
            }
            if (!snapshot.has("progress") || !snapshot.get("progress").canConvertToInt()) {
                return;
            }
            int progress = Math.max(0, Math.min(99, snapshot.get("progress").asInt()));
            updateProgress(request, progress);
        } catch (Exception exception) {
            log.debug("Ignore malformed LDD progress snapshot: {}", progressFile, exception);
        }
    }

    private void finish(DefenseTaskRequest request, String status, int progress) {
        taskMapper.finishForRun(request.getTaskId(), request.getRunId(), status, progress,
                java.time.LocalDateTime.now());
    }

    private void fail(DefenseTaskRequest request, ExecutionContext context, String startedAt,
                      String errorCode, String reason) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("executor", "spring-boot-python-process");
        result.put("stage", "pre_defense");
        result.put("status", "failed");
        result.put("message", "LDD 执行失败");
        result.put("run_id", request.getRunId());
        result.put("attempt", request.getAttempt());
        result.put("error_code", errorCode);
        result.put("failure_reason", StringUtils.hasText(reason) ? reason : "未知执行错误");
        result.put("started_at", startedAt);
        result.put("finished_at", OffsetDateTime.now().toString());
        result.set("artifacts", artifacts(context));
        taskMapper.updateResultForRun(request.getTaskId(), request.getRunId(), null,
                result.toString());
        taskMapper.finishForRun(request.getTaskId(), request.getRunId(), "failed", 0,
                java.time.LocalDateTime.now());
    }

    private ObjectNode artifacts(ExecutionContext context) {
        ObjectNode artifacts = objectMapper.createObjectNode();
        artifacts.put("type", "executor_workspace");
        artifacts.put("available", context != null);
        if (context != null) {
            artifacts.put("path", context.taskDir.toString());
            var logs = objectMapper.createArrayNode();
            logs.add(context.stdoutFile.toString());
            logs.add(context.stderrFile.toString());
            artifacts.set("logs", logs);
        }
        return artifacts;
    }

    private boolean isCanceled(DefenseTaskRequest request) {
        return request.getRunId() != null && canceledRuns.contains(request.getRunId());
    }

    private boolean isFailedResult(String raw) {
        try {
            return "failed".equals(text(objectMapper.readTree(raw), "status"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readIfExists(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    private void requireText(JsonNode node, String field) {
        if (!StringUtils.hasText(text(node, field))) {
            throw new IllegalStateException("RESULT_PROTOCOL_ERROR: " + field + " 是必填项");
        }
    }

    private String text(JsonNode node, String field) {
        return node == null || node.get(field) == null ? null : node.get(field).asText();
    }

    private String classify(Exception exception) {
        String message = exception.getMessage();
        if (StringUtils.hasText(message) && message.contains(":")) {
            String code = message.substring(0, message.indexOf(':'));
            if (List.of("EXECUTOR_NOT_CONFIGURED", "TASK_TIMEOUT", "PYTHON_PROCESS_EXITED",
                    "RESULT_MISSING", "RESULT_INVALID_JSON", "RESULT_PROTOCOL_ERROR",
                    "GPU_OOM", "CUDA_UNAVAILABLE", "GPU_LOCK_BUSY",
                    "GPU_LEASE_LOST", "GPU_BUSY").contains(code)) {
                return code;
            }
        }
        return "PYTHON_EXECUTION_ERROR";
    }

    /**
     * Stable failure code for a child process that died without writing result.json.
     * Text matching only; never used to infer success.
     */
    static String classifyProcessFailure(String output) {
        String lowered = output == null ? "" : output.toLowerCase(java.util.Locale.ROOT);
        if (lowered.contains("cuda out of memory")
                || lowered.contains("outofmemoryerror")
                || lowered.contains("cublas_status_alloc_failed")
                || lowered.contains("cudnn_status_alloc_failed")) {
            return "GPU_OOM";
        }
        if (lowered.contains("no cuda-capable device")
                || lowered.contains("cuda driver version")
                || lowered.contains("torch not compiled with cuda")
                || lowered.contains("cuda_error_no_device")
                || lowered.contains("no device of requested type")) {
            return "CUDA_UNAVAILABLE";
        }
        if (lowered.contains("gpu_lock_busy") || lowered.contains(".security-model-vab.lock")) {
            return "GPU_LOCK_BUSY";
        }
        return "PYTHON_PROCESS_EXITED";
    }

    private String stripCode(String message) {
        if (!StringUtils.hasText(message)) {
            return "未知执行错误";
        }
        int index = message.indexOf(':');
        return index > 0 ? message.substring(index + 1).trim() : message;
    }

    private record ExecutionContext(Path taskDir, Path requestFile, Path resultFile,
                                    Path stdoutFile, Path stderrFile, Path progressFile) {
    }

    private static class CanceledException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
