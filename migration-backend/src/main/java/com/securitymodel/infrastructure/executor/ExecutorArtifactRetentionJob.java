package com.securitymodel.infrastructure.executor;

import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Retention for executor attempt directories. Default is disabled (retention-days <= 0);
 * deletion only ever touches an expired, non-current attempt of a terminal task.
 */
@Component
public class ExecutorArtifactRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ExecutorArtifactRetentionJob.class);
    private static final List<String> TERMINAL_STATUSES = List.of("success", "failed", "canceled");

    private final PreDefenseTaskMapper taskMapper;
    private final Path workspace;
    private final long retentionDays;

    public ExecutorArtifactRetentionJob(
            PreDefenseTaskMapper taskMapper,
            @Value("${security-model.executor.workspace:}") String workspace,
            @Value("${security-model.executor.artifacts.retention-days:0}") long retentionDays) {
        this.taskMapper = taskMapper;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${security-model.executor.artifacts.cleanup-interval-ms:3600000}",
            initialDelayString = "${security-model.executor.artifacts.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        if (retentionDays <= 0 || !Files.isDirectory(workspace)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int removed = 0;
        try (Stream<Path> stream = Files.list(workspace)) {
            for (Path taskDir : stream.filter(Files::isDirectory).toList()) {
                removed += cleanupTask(taskDir, cutoff);
            }
        } catch (IOException exception) {
            log.warn("Executor artifact cleanup failed: workspace={}, reason={}",
                    workspace, exception.getMessage());
            return;
        }
        if (removed > 0) {
            log.info("Executor artifact cleanup removed {} attempt dirs older than {} days", removed, retentionDays);
        }
    }

    private int cleanupTask(Path taskDir, Instant cutoff) {
        Long taskId = parseTaskId(taskDir.getFileName().toString());
        if (taskId == null) {
            return 0;
        }
        PreDefenseTask task;
        try {
            task = taskMapper.selectById(taskId);
        } catch (RuntimeException exception) {
            log.warn("Skip executor cleanup, task lookup failed: taskId={}, reason={}",
                    taskId, exception.getMessage());
            return 0;
        }
        if (task == null || !TERMINAL_STATUSES.contains(task.getStatus())) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> stream = Files.list(taskDir)) {
            for (Path attemptDir : stream.filter(Files::isDirectory).toList()) {
                Integer attempt = parseAttempt(attemptDir.getFileName().toString());
                if (attempt == null || attempt.equals(task.getCurrentAttempt())) {
                    continue;
                }
                if (!isExpired(attemptDir, cutoff)) {
                    continue;
                }
                if (deleteTree(attemptDir)) {
                    removed++;
                }
            }
        } catch (IOException exception) {
            log.warn("Skip executor cleanup: dir={}, reason={}", taskDir, exception.getMessage());
        }
        return removed;
    }

    private boolean isExpired(Path attemptDir, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(attemptDir).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean deleteTree(Path attemptDir) {
        Path resolved = resolveSafe(attemptDir);
        if (resolved == null) {
            log.warn("Refuse to delete a symlink/junction or out-of-workspace artifact: dir={}", attemptDir);
            return false;
        }
        try (Stream<Path> stream = Files.walk(resolved)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("Could not delete executor artifact: path={}, reason={}",
                            path, exception.getMessage());
                }
            });
        } catch (IOException exception) {
            log.warn("Could not walk executor artifact dir: dir={}, reason={}",
                    attemptDir, exception.getMessage());
            return false;
        }
        return !Files.exists(resolved);
    }

    /**
     * Only a real (non-link) directory whose resolved path stays inside the resolved workspace
     * may be deleted. Comparing normalized strings alone would follow a junction out of bounds.
     */
    Path resolveSafe(Path dir) {
        try {
            Path real = dir.toRealPath();
            Path workspaceReal = workspace.toRealPath();
            if (!real.startsWith(workspaceReal)) {
                return null;
            }
            if (!real.equals(dir.toAbsolutePath().normalize())) {
                return null;
            }
            return real;
        } catch (IOException exception) {
            return null;
        }
    }

    private Long parseTaskId(String name) {
        String prefix = "pre_defense-";
        if (!name.startsWith(prefix)) {
            return null;
        }
        try {
            return Long.parseLong(name.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer parseAttempt(String name) {
        String prefix = "attempt-";
        if (!name.startsWith(prefix)) {
            return null;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
