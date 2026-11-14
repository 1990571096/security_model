package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminates a live executor process together with its known descendants and waits until every
 * known process is gone. Callers must not release the GPU slot unless {@code ensureGone} returns
 * {@code true}.
 */
@Component
public class ExecutorProcessGuard {

    private static final Logger log = LoggerFactory.getLogger(ExecutorProcessGuard.class);
    private static final String CHILDREN = "python-children.json";

    private final ObjectMapper objectMapper;

    public ExecutorProcessGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Kill (when requested) and confirm the whole known tree is gone within the timeout. */
    public boolean ensureGone(Process process, Path taskDir, long waitMillis, boolean kill) {
        List<ProcessHandle> known = new ArrayList<>();
        List<ProcessHandle> unverified = new ArrayList<>();
        ProcessHandle root = null;
        if (process != null) {
            root = process.toHandle();
            process.descendants().forEach(known::add);
            known.add(root);
        }
        recordedChildren(taskDir, root, known, unverified);
        boolean recordReliable = recordReliable(taskDir, root);
        if (kill) {
            known.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitMillis);
        while (System.currentTimeMillis() < deadline) {
            if (known.stream().noneMatch(ProcessHandle::isAlive)
                    && unverified.stream().noneMatch(ProcessHandle::isAlive)
                    && recordReliable) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean gone = known.stream().noneMatch(ProcessHandle::isAlive)
                && unverified.stream().noneMatch(ProcessHandle::isAlive)
                && recordReliable;
        if (!gone) {
            log.error("Executor process tree still alive or unverified after wait: known={}, unverified={}, "
                            + "recordReliable={}",
                    known.size(), unverified.size(), recordReliable);
        }
        return gone;
    }

    /**
     * A missing or corrupt children record is not an empty tree. It is reliable only when
     * the live Java root handle still owns the tree (its descendants are enumerable), or
     * the adapter has written a valid record. The adapter writes an empty {@code pids}
     * array after the training child exits, so a normal success leaves positive evidence.
     */
    private boolean recordReliable(Path taskDir, ProcessHandle root) {
        if (taskDir == null) {
            return root != null;
        }
        Path file = taskDir.resolve(CHILDREN);
        if (Files.isRegularFile(file)) {
            return isValidChildrenRecord(file);
        }
        return root != null && root.isAlive();
    }

    private boolean isValidChildrenRecord(Path file) {
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return node != null && node.path("pids").isArray();
        } catch (IOException | RuntimeException exception) {
            log.warn("Unreadable python-children record: file={}, reason={}", file, exception.getMessage());
            return false;
        }
    }

    /**
     * Recorded child pids are only killed when their live parent chain still points at the
     * verified root. A bare pid may have been reused by an unrelated process, so an
     * unverifiable live child keeps the GPU slot quarantined instead of being killed.
     */
    private void recordedChildren(Path taskDir, ProcessHandle root,
                                  List<ProcessHandle> known, List<ProcessHandle> unverified) {
        if (taskDir == null) {
            return;
        }
        Path file = taskDir.resolve(CHILDREN);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonNode item : node.path("pids")) {
                long pid = item.asLong(-1);
                if (pid <= 0) {
                    continue;
                }
                ProcessHandle.of(pid).ifPresent(handle -> {
                    if (root != null && isDescendantOf(handle, root)) {
                        known.add(handle);
                    } else {
                        unverified.add(handle);
                    }
                });
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Unreadable python-children record: file={}, reason={}", file, exception.getMessage());
        }
    }

    private boolean isDescendantOf(ProcessHandle child, ProcessHandle root) {
        // A dead root handle can have its pid reused by an unrelated new process. Never
        // treat a parent-chain pid match as identity proof unless the root is still alive.
        if (!root.isAlive()) {
            return false;
        }
        ProcessHandle current = child.parent().orElse(null);
        while (current != null) {
            if (current.pid() == root.pid()) {
                return true;
            }
            current = current.parent().orElse(null);
        }
        return false;
    }
}
