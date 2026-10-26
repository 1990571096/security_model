package com.securitymodel.application.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.task.ExecutionFingerprintService;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class PreDefenseLogQueryService {

    private static final int MAX_READ_BYTES = 256 * 1024;
    private final PreDefenseTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final ExecutionFingerprintService fingerprintService;

    public PreDefenseLogQueryService(
            PreDefenseTaskMapper taskMapper,
            ObjectMapper objectMapper,
            ExecutionFingerprintService fingerprintService) {
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.fingerprintService = fingerprintService;
    }

    public Map<String, Object> getLogs(Long taskId, Map<String, Long> offsets) {
        PreDefenseTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> logs = new ArrayList<>();
        result.put("taskId", taskId);
        result.put("available", false);
        result.put("logs", logs);
        result.put("incremental", offsets != null && !offsets.isEmpty());
        try {
            Path artifactRoot = artifactRoot(task);
            JsonNode progress = readProgress(task, artifactRoot);
            if (progress != null && !progress.isMissingNode()) {
                result.put("progress", progress);
            }
            List<Path> paths = new ArrayList<>();
            if (StringUtils.hasText(task.getCleaningResult())) {
                JsonNode artifacts = objectMapper.readTree(task.getCleaningResult()).path("artifacts");
                if (!"running".equals(task.getStatus()) && artifacts.path("logs").isArray()) {
                    artifacts.path("logs").forEach(item -> paths.add(Paths.get(item.asText())));
                }
            }
            if ("running".equals(task.getStatus()) && task.getCurrentAttempt() != null) {
                paths.clear();
                Path root = Paths.get(fingerprintService.getWorkspace(), "pre_defense-" + taskId,
                        "attempt-" + task.getCurrentAttempt());
                paths.add(root.resolve("stdout.log"));
                paths.add(root.resolve("stderr.log"));
            }
            for (Path path : paths) {
                Path normalized = path.toAbsolutePath().normalize();
                if (artifactRoot != null && append(normalized, artifactRoot, offsets, logs)) {
                    result.put("available", true);
                }
            }
            Map<String, Long> nextOffsets = new LinkedHashMap<>();
            for (Map<String, Object> item : logs) {
                nextOffsets.put(String.valueOf(item.get("relativePath")),
                        ((Number) item.get("offset")).longValue());
            }
            result.put("offsets", nextOffsets);
            result.put("message", logs.isEmpty() ? "暂未找到可读取的执行日志" : "执行日志读取成功");
        } catch (Exception exception) {
            result.put("message", "执行日志读取失败");
        }
        return result;
    }

    private JsonNode readProgress(PreDefenseTask task, Path artifactRoot) {
        List<Path> candidates = new ArrayList<>();
        if (task.getCurrentAttempt() != null) {
            candidates.add(Paths.get(fingerprintService.getWorkspace(),
                    "pre_defense-" + task.getTaskId(),
                    "attempt-" + task.getCurrentAttempt(),
                    "progress.json"));
        }
        if (artifactRoot != null) {
            candidates.add(artifactRoot.resolve("progress.json"));
        }
        for (Path candidate : candidates) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    JsonNode progress = objectMapper.readTree(Files.readString(normalized, StandardCharsets.UTF_8));
                    if (progress != null && progress.isObject()) {
                        return progress;
                    }
                }
            } catch (Exception ignored) {
                // The adapter replaces this file atomically; a partial read is retried on the next poll.
            }
        }
        return null;
    }

    private boolean append(Path path, Path root, Map<String, Long> offsets,
                           List<Map<String, Object>> logs) throws IOException {
        if (!Files.isRegularFile(path) || !isReadable(path)) {
            return false;
        }
        Path realRoot = root.toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realRoot)) {
            return false;
        }
        long size = Files.size(realPath);
        String relative = realRoot.relativize(realPath).toString();
        long requested = offsets == null ? 0 : Math.max(0,
                Optional.ofNullable(offsets.get(relative))
                        .orElseGet(() -> offsets.getOrDefault(realPath.getFileName().toString(), 0L)));
        boolean reset = requested > size;
        long start = reset ? 0 : requested;
        int length = (int) Math.min(MAX_READ_BYTES, size - start);
        byte[] bytes = new byte[length];
        try (RandomAccessFile file = new RandomAccessFile(realPath.toFile(), "r")) {
            file.seek(start);
            int read = file.read(bytes);
            if (read < 0) {
                read = 0;
            }
            if (read < bytes.length) {
                bytes = Arrays.copyOf(bytes, read);
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", realPath.getFileName().toString());
        item.put("path", realPath.toString());
        item.put("relativePath", relative);
        item.put("content", new String(bytes, StandardCharsets.UTF_8));
        item.put("size", size);
        item.put("startOffset", start);
        item.put("offset", start + bytes.length);
        item.put("reset", reset);
        item.put("truncated", start + bytes.length < size);
        logs.add(item);
        if (!logs.isEmpty()) {
            // The frontend sends the returned offset map on the next poll.
        }
        return true;
    }

    private Path artifactRoot(PreDefenseTask task) {
        try {
            if ("running".equals(task.getStatus()) && task.getCurrentAttempt() != null) {
                return Paths.get(fingerprintService.getWorkspace(), "pre_defense-" + task.getTaskId(),
                        "attempt-" + task.getCurrentAttempt()).toAbsolutePath().normalize();
            }
            if (StringUtils.hasText(task.getCleaningResult())) {
                String path = objectMapper.readTree(task.getCleaningResult())
                        .path("artifacts").path("path").asText();
                if (StringUtils.hasText(path) && Files.isDirectory(Paths.get(path))) {
                    return Paths.get(path).toAbsolutePath().normalize();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isReadable(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".log") || name.endsWith(".txt");
    }
}
