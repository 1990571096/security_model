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
        if (process != null) {
            process.descendants().forEach(known::add);
            known.add(process.toHandle());
        }
        known.addAll(readRecordedChildren(taskDir));
        if (kill) {
            known.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitMillis);
        while (System.currentTimeMillis() < deadline) {
            if (known.stream().noneMatch(ProcessHandle::isAlive)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean gone = known.stream().noneMatch(ProcessHandle::isAlive);
        if (!gone) {
            log.error("Executor process tree still alive after wait: known={}", known.size());
        }
        return gone;
    }

    private List<ProcessHandle> readRecordedChildren(Path taskDir) {
        List<ProcessHandle> handles = new ArrayList<>();
        if (taskDir == null) {
            return handles;
        }
        Path file = taskDir.resolve(CHILDREN);
        if (!Files.isRegularFile(file)) {
            return handles;
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonNode item : node.path("pids")) {
                long pid = item.asLong(-1);
                if (pid > 0) {
                    ProcessHandle.of(pid).ifPresent(handles::add);
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Unreadable python-children record: file={}, reason={}", file, exception.getMessage());
        }
        return handles;
    }
}
