package me.zhengjie.modules.system.service.impl;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.DuringDefenseMapper;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.executor.DefenseTaskExecutor;
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
        try {
            updateStatus(stage, taskId, "running", 10);
            String resultJson = invokePython(request, stage, startedAt);
            persistResult(stage, taskId, resultJson);
            finishTask(stage, taskId, "success", 100);
            log.info("Python {} task {} completed", stage, taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            persistResult(stage, taskId, buildFailureResult(stage, startedAt,
                    "TASK_INTERRUPTED", "Task was interrupted"));
            finishTask(stage, taskId, "failed", 0);
            log.warn("Python {} task {} interrupted", stage, taskId);
        } catch (Exception e) {
            persistResult(stage, taskId, buildFailureResult(stage, startedAt,
                    "PYTHON_EXECUTION_ERROR", e.getMessage()));
            finishTask(stage, taskId, "failed", 0);
            log.error("Python {} task {} failed", stage, taskId, e);
        }
    }

    private String invokePython(DefenseTaskRequest request, String stage, String startedAt)
            throws IOException, InterruptedException {
        if (!StringUtils.hasText(pythonScript)) {
            throw new IllegalStateException("security-model.executor.python.script is not configured");
        }

        Path taskDir = Paths.get(executorWorkspace, stage + "-" + request.getTaskId());
        Files.createDirectories(taskDir);

        Path requestFile = taskDir.resolve("request.json");
        Path resultFile = taskDir.resolve("result.json");
        Path stdoutFile = taskDir.resolve("stdout.log");
        Path stderrFile = taskDir.resolve("stderr.log");

        Files.write(requestFile, JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8));

        List<String> command = Arrays.asList(
                pythonExecutable,
                pythonScript,
                "--stage", stage,
                "--request", requestFile.toAbsolutePath().toString(),
                "--result", resultFile.toAbsolutePath().toString()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(executorWorkspace));
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Python executor timed out after " + timeoutSeconds + " seconds");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Python executor exited with code " + process.exitValue()
                    + ", stderr: " + readFileIfExists(stderrFile));
        }

        updateStatus(stage, request.getTaskId(), "running", 90);
        if (Files.exists(resultFile)) {
            return new String(Files.readAllBytes(resultFile), StandardCharsets.UTF_8);
        }
        return buildSuccessResult(stage, startedAt, taskDir);
    }

    private String buildSuccessResult(String stage, String startedAt, Path taskDir) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "python-process");
        result.put("stage", stage);
        result.put("status", "success");
        result.put("message", "python executor completed without result.json");
        result.put("started_at", startedAt);
        result.put("finished_at", LocalDateTime.now().toString());
        result.put("artifacts", buildArtifacts(taskDir));
        return JSON.toJSONString(result);
    }

    private String buildFailureResult(String stage, String startedAt, String errorCode, String failureReason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "python-process");
        result.put("stage", stage);
        result.put("status", "failed");
        result.put("message", "python executor failed");
        result.put("error_code", errorCode);
        result.put("failure_reason", StringUtils.hasText(failureReason) ? failureReason : "Unknown execution error");
        result.put("started_at", startedAt);
        result.put("finished_at", LocalDateTime.now().toString());
        result.put("artifacts", new LinkedHashMap<String, Object>());
        return JSON.toJSONString(result);
    }

    private Map<String, Object> buildArtifacts(Path taskDir) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("type", "executor_workspace");
        artifacts.put("available", true);
        artifacts.put("path", taskDir.toAbsolutePath().toString());
        artifacts.put("message", "Python executor workspace");
        return artifacts;
    }

    private String readFileIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private void persistResult(String stage, Long taskId, String resultJson) {
        if ("pre_defense".equals(stage)) {
            preDefenseMapper.updateResult(taskId, null, resultJson);
            return;
        }
        duringDefenseMapper.updateTrainingResult(taskId, null, resultJson);
    }

    private void updateStatus(String stage, Long taskId, String status, Integer progress) {
        if ("pre_defense".equals(stage)) {
            preDefenseMapper.updateStatus(taskId, status, progress);
            return;
        }
        duringDefenseMapper.updateStatus(taskId, status, progress);
    }

    private void finishTask(String stage, Long taskId, String status, Integer progress) {
        if ("pre_defense".equals(stage)) {
            PreDefense task = preDefenseMapper.selectById(taskId);
            if (task == null) {
                return;
            }
            task.setStatus(status);
            task.setProgress(progress);
            task.setEndTime(LocalDateTime.now());
            preDefenseMapper.updateById(task);
            return;
        }

        DuringDefense task = duringDefenseMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(status);
        task.setProgress(progress);
        task.setEndTime(LocalDateTime.now());
        duringDefenseMapper.updateById(task);
    }
}
