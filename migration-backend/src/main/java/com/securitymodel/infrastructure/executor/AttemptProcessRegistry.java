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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Records the executor child process (and its spawned children) next to the attempt artifacts,
 * and decides whether a restart may safely release the GPU slot.
 *
 * <p>Invariant: {@link #prepare(Path)} is written <em>before</em> the child starts and the launch
 * aborts if it cannot be written. A missing record therefore proves the child never started;
 * anything recorded but not positively matched is {@link ReapOutcome#UNKNOWN} and must keep the
 * slot quarantined.</p>
 */
@Component
public class AttemptProcessRegistry {

    private static final Logger log = LoggerFactory.getLogger(AttemptProcessRegistry.class);
    private static final String RECORD = "process.json";
    private static final String CHILDREN = "python-children.json";

    private final Path workspace;
    private final ObjectMapper objectMapper;

    public AttemptProcessRegistry(
            ObjectMapper objectMapper,
            @Value("${security-model.executor.workspace:}") String workspace) {
        this.objectMapper = objectMapper;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
    }

    /** Write the pre-start marker; throws so the caller does not start a process it cannot trace. */
    public void prepare(Path taskDir) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", "starting");
        node.put("recorded_at", Instant.now().toString());
        Files.writeString(taskDir.resolve(RECORD),
                objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
    }

    public void record(Path taskDir, Process process) {
        if (taskDir == null || process == null) {
            return;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("state", "running");
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

    /** Outcome of the pre-recovery probe. Only EXITED, REAPED and NOT_STARTED allow releasing. */
    public enum ReapOutcome {
        /** No pre-start record: the child never started (prepare precedes start). */
        NOT_STARTED,
        /** Process identity positively checked and confirmed gone. */
        EXITED,
        /** Live process with matching identity was killed and is confirmed gone. */
        REAPED,
        /** Live process could not be killed; the slot must stay quarantined. */
        KILL_FAILED,
        /** Identity or children cannot be verified; do not kill, do not release. */
        UNKNOWN
    }

    public ReapOutcome reapIfOrphaned(Long taskId, Integer attempt) {
        Path recordFile = locate(taskId, attempt, RECORD);
        if (recordFile == null) {
            return ReapOutcome.NOT_STARTED;
        }
        JsonNode record = readJson(recordFile);
        if (record == null) {
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

    private Path locate(Long taskId, Integer attempt, String fileName) {
        if (taskId == null || attempt == null || workspace.toString().isEmpty()) {
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
