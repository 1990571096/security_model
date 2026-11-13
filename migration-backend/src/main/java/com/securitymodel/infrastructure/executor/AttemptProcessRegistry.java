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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Records the executor child process (and its spawned children) next to the attempt artifacts,
 * and decides whether a restart may safely release the GPU slot.
 *
 * <p>Invariant: {@link #prepare(Path)} is written <em>before</em> the child starts, with
 * {@code schema_version=2}, and the launch aborts if it cannot be written. A missing or
 * unreadable record does <strong>not</strong> prove that the child never started (the file may
 * have been deleted, or an older version never wrote one), so it is {@link ReapOutcome#UNKNOWN}
 * and keeps the slot quarantined. Only a positive identity match can kill.</p>
 */
@Component
public class AttemptProcessRegistry {

    private static final Logger log = LoggerFactory.getLogger(AttemptProcessRegistry.class);
    private static final String RECORD = "process.json";
    private static final String CHILDREN = "python-children.json";
    private static final int SCHEMA_VERSION = 2;

    private final Path workspace;
    private final ObjectMapper objectMapper;

    public AttemptProcessRegistry(
            ObjectMapper objectMapper,
            @Value("${security-model.executor.workspace:}") String workspace) {
        this.objectMapper = objectMapper;
        String configured = workspace == null ? "" : workspace.trim();
        // A blank workspace must never fall back to the current directory (the repository root).
        this.workspace = configured.isEmpty() ? null : Paths.get(configured).toAbsolutePath().normalize();
    }

    /** Write the pre-start marker; throws so the caller does not start a process it cannot trace. */
    public void prepare(Path taskDir) throws IOException {
        if (taskDir == null) {
            throw new IOException("未提供 attempt 目录，无法记录执行进程");
        }
        Path target = requireInsideWorkspace(taskDir.resolve(RECORD));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schema_version", SCHEMA_VERSION);
        node.put("state", "starting");
        node.put("recorded_at", Instant.now().toString());
        writeAtomically(target, objectMapper.writeValueAsString(node));
    }

    /**
     * Rewrites the marker as running. Returns normally only after the record is durable;
     * the caller must terminate the child when this throws.
     */
    public void record(Path taskDir, Process process) throws IOException {
        if (taskDir == null || process == null) {
            throw new IOException("缺少进程或 attempt 目录，无法记录执行进程");
        }
        Path target = requireInsideWorkspace(taskDir.resolve(RECORD));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schema_version", SCHEMA_VERSION);
        node.put("state", "running");
        node.put("pid", process.pid());
        node.put("command", process.info().command().orElse(""));
        node.put("started_at", process.info().startInstant().map(Instant::toString).orElse(null));
        node.put("recorded_at", Instant.now().toString());
        writeAtomically(target, objectMapper.writeValueAsString(node));
    }

    /** Outcome of the pre-recovery probe. Only EXITED and REAPED allow releasing the slot. */
    public enum ReapOutcome {
        /** A versioned record positively proves the launch never began (R3 intent), not used in R2. */
        NOT_STARTED,
        /** Process identity positively checked and confirmed gone. */
        EXITED,
        /** Live process with matching identity was killed and is confirmed gone. */
        REAPED,
        /** Live process could not be killed; the slot must stay quarantined. */
        KILL_FAILED,
        /** Record missing/unreadable/legacy or identity/children cannot be verified; never kill. */
        UNKNOWN
    }

    public ReapOutcome reapIfOrphaned(Long taskId, Integer attempt) {
        Path recordFile = locate(taskId, attempt, RECORD);
        if (recordFile == null) {
            // The record may have been deleted, or an older version never wrote one.
            return ReapOutcome.UNKNOWN;
        }
        JsonNode record = readJson(recordFile);
        if (record == null || record.path("schema_version").asInt(-1) != SCHEMA_VERSION) {
            return ReapOutcome.UNKNOWN;
        }
        long pid = record.path("pid").asLong(-1);
        if (pid <= 0) {
            // "starting" marker without a pid: the child may have started before the crash.
            return ReapOutcome.UNKNOWN;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        List<ProcessHandle> recordedChildren = readChildren(taskId, attempt);
        if (recordedChildren == null) {
            return ReapOutcome.UNKNOWN;
        }
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return anyAlive(recordedChildren) ? ReapOutcome.UNKNOWN : ReapOutcome.EXITED;
        }
        Instant recordedStart = parseInstant(record.path("started_at").asText(null));
        Instant actualStart = handle.get().info().startInstant().orElse(null);
        if (recordedStart == null || actualStart == null) {
            return ReapOutcome.UNKNOWN;
        }
        if (!recordedStart.equals(actualStart)) {
            // The pid was reused by an unrelated process: our child is gone, do not kill.
            log.warn("Executor pid reused by another process, skip kill: taskId={}, attempt={}, pid={}",
                    taskId, attempt, pid);
            return anyAlive(recordedChildren) ? ReapOutcome.UNKNOWN : ReapOutcome.EXITED;
        }
        List<ProcessHandle> tree = new ArrayList<>(handle.get().descendants().toList());
        tree.addAll(recordedChildren);
        tree.forEach(ProcessHandle::destroyForcibly);
        handle.get().destroyForcibly();
        if (waitForAll(tree, handle.get(), 3000)) {
            log.warn("Killed orphaned executor process tree: taskId={}, attempt={}, pid={}, known={}",
                    taskId, attempt, pid, tree.size());
            return ReapOutcome.REAPED;
        }
        log.error("Orphaned executor process tree still alive after kill: taskId={}, attempt={}, pid={}",
                taskId, attempt, pid);
        return ReapOutcome.KILL_FAILED;
    }

    private List<ProcessHandle> readChildren(Long taskId, Integer attempt) {
        Path file = locate(taskId, attempt, CHILDREN);
        if (file == null) {
            return List.of();
        }
        JsonNode node = readJson(file);
        if (node == null) {
            return null;
        }
        List<ProcessHandle> handles = new ArrayList<>();
        for (JsonNode item : node.path("pids")) {
            long pid = item.asLong(-1);
            if (pid > 0) {
                ProcessHandle.of(pid).ifPresent(handles::add);
            }
        }
        return handles;
    }

    private boolean anyAlive(List<ProcessHandle> handles) {
        return handles.stream().anyMatch(ProcessHandle::isAlive);
    }

    private boolean waitForAll(List<ProcessHandle> tree, ProcessHandle root, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!root.isAlive() && !anyAlive(tree)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return !root.isAlive() && !anyAlive(tree);
            }
        }
        return !root.isAlive() && !anyAlive(tree);
    }

    private JsonNode readJson(Path file) {
        try {
            return objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            log.warn("Unreadable executor process metadata: file={}, reason={}", file, exception.getMessage());
            return null;
        }
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".process-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path requireInsideWorkspace(Path file) throws IOException {
        if (workspace == null) {
            throw new IOException("未配置 executor workspace，拒绝在未知目录记录进程");
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) {
            throw new IOException("attempt 目录不在受控 workspace 内");
        }
        return normalized;
    }

    private Path locate(Long taskId, Integer attempt, String fileName) {
        if (taskId == null || attempt == null || workspace == null) {
            return null;
        }
        Path file = workspace.resolve("pre_defense-" + taskId)
                .resolve("attempt-" + attempt)
                .resolve(fileName)
                .toAbsolutePath()
                .normalize();
        if (!file.startsWith(workspace) || !Files.isRegularFile(file)) {
            return null;
        }
        return file;
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
