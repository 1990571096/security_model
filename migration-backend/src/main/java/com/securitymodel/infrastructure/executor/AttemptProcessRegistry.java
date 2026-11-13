package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

/**
 * Records the executor child process next to its attempt artifacts and verifies it is really gone
 * before a restart recovery releases the GPU lease.
 */
@Component
public class AttemptProcessRegistry {

    private static final Logger log = LoggerFactory.getLogger(AttemptProcessRegistry.class);
    private static final String RECORD = "process.json";

    private final Path workspace;
    private final ObjectMapper objectMapper;

    public AttemptProcessRegistry(
            ObjectMapper objectMapper,
            @Value("${security-model.executor.workspace:}") String workspace) {
        this.objectMapper = objectMapper;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
    }

    public void record(Path taskDir, Process process) {
        if (taskDir == null || process == null) {
            return;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("pid", process.pid());
            node.put("command", process.info().command().orElse(""));
            node.put("started_at", process.info().startInstant().map(Instant::toString).orElse(null));
            node.put("recorded_at", Instant.now().toString());
            Files.writeString(taskDir.resolve(RECORD),
                    objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not record executor process: dir={}, reason={}", taskDir, exception.getMessage());
        }
    }

    /** Outcome of the pre-recovery probe. Only {@link #KILL_FAILED} forbids releasing the lease. */
    public enum ReapOutcome {
        NO_RECORD, EXITED, REAPED, KILL_FAILED
    }

    public ReapOutcome reapIfOrphaned(Long taskId, Integer attempt) {
        Path recordFile = locateRecord(taskId, attempt);
        if (recordFile == null) {
            return ReapOutcome.NO_RECORD;
        }
        JsonNode record;
        try {
            record = objectMapper.readTree(Files.readString(recordFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            log.warn("Unreadable executor process record, treat as unknown: file={}, reason={}",
                    recordFile, exception.getMessage());
            return ReapOutcome.NO_RECORD;
        }
        long pid = record.path("pid").asLong(-1);
        if (pid <= 0) {
            return ReapOutcome.NO_RECORD;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return ReapOutcome.EXITED;
        }
        Instant recordedStart = parseInstant(record.path("started_at").asText(null));
        Instant actualStart = handle.get().info().startInstant().orElse(null);
        if (recordedStart != null && actualStart != null && !recordedStart.equals(actualStart)) {
            // The pid was reused by an unrelated process: our child is gone, do not kill.
            log.warn("Executor pid reused by another process, skip kill: taskId={}, attempt={}, pid={}",
                    taskId, attempt, pid);
            return ReapOutcome.EXITED;
        }
        handle.get().descendants().forEach(ProcessHandle::destroyForcibly);
        handle.get().destroyForcibly();
        if (waitForExit(handle.get(), 2000)) {
            log.warn("Killed orphaned executor process: taskId={}, attempt={}, pid={}",
                    taskId, attempt, pid);
            return ReapOutcome.REAPED;
        }
        log.error("Orphaned executor process still alive after kill: taskId={}, attempt={}, pid={}",
                taskId, attempt, pid);
        return ReapOutcome.KILL_FAILED;
    }

    private Path locateRecord(Long taskId, Integer attempt) {
        if (taskId == null || attempt == null || workspace.toString().isEmpty()) {
            return null;
        }
        Path record = workspace.resolve("pre_defense-" + taskId)
                .resolve("attempt-" + attempt)
                .resolve(RECORD)
                .toAbsolutePath()
                .normalize();
        if (!record.startsWith(workspace) || !Files.isRegularFile(record)) {
            return null;
        }
        return record;
    }

    private boolean waitForExit(ProcessHandle handle, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!handle.isAlive()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return !handle.isAlive();
            }
        }
        return !handle.isAlive();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
