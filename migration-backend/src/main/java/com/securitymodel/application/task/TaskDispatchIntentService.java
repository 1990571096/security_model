package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.persistence.mapper.TaskDispatchIntentMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistent dispatch intent. The row is written in the same transaction as the task claim
 * and GPU lease, then conditionally promoted {@code pending -> submitting -> submitted}.
 * A unique key prevents duplicate intents; the conditional UPDATE, not the key, elects the
 * single dispatcher.
 */
@Service
public class TaskDispatchIntentService {

    private static final int SCHEMA_VERSION = 1;

    private final TaskDispatchIntentMapper mapper;
    private final ObjectMapper objectMapper;

    public TaskDispatchIntentService(TaskDispatchIntentMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void createPending(DefenseTaskRequest request, String runtimeHash, LocalDateTime now) {
        TaskDispatchIntent intent = new TaskDispatchIntent();
        intent.setTaskId(request.getTaskId());
        intent.setRunId(request.getRunId());
        intent.setAttempt(request.getAttempt());
        intent.setStatus(TaskDispatchIntent.PENDING);
        intent.setSchemaVersion(SCHEMA_VERSION);
        intent.setRequestJson(writeRequest(request));
        intent.setRequestHash(sha256(intent.getRequestJson()));
        intent.setRuntimeHash(runtimeHash);
        intent.setCreatedAt(now);
        intent.setUpdatedAt(now);
        mapper.insert(intent);
    }

    public TaskDispatchIntent findByRun(Long taskId, String runId, Integer attempt) {
        if (taskId == null || runId == null || attempt == null) {
            return null;
        }
        return mapper.findByRun(taskId, runId, attempt);
    }

    public boolean markSubmitting(Long taskId, String runId, Integer attempt, LocalDateTime now) {
        return mapper.markSubmitting(taskId, runId, attempt, now) == 1;
    }

    public void markSubmitted(Long taskId, String runId, Integer attempt, LocalDateTime now) {
        mapper.markSubmitted(taskId, runId, attempt, now);
    }

    public void abandon(Long taskId, String runId, Integer attempt, String errorCode, LocalDateTime now) {
        mapper.abandon(taskId, runId, attempt, errorCode, now);
    }

    /** Conditional terminal transition used by expiry; only the caller that wins may release. */
    public boolean abandonPending(Long taskId, String runId, Integer attempt, String errorCode,
                                  LocalDateTime now) {
        return mapper.abandonPending(taskId, runId, attempt, errorCode, now) == 1;
    }

    public List<TaskDispatchIntent> findPendingOlderThan(LocalDateTime cutoff, int limit) {
        return mapper.findPendingOlderThan(cutoff, limit);
    }

    public List<TaskDispatchIntent> findSubmittingOlderThan(LocalDateTime cutoff, int limit) {
        return mapper.findSubmittingOlderThan(cutoff, limit);
    }

    public DefenseTaskRequest readRequest(TaskDispatchIntent intent) {
        if (intent.getSchemaVersion() == null || intent.getSchemaVersion() != SCHEMA_VERSION) {
            throw new TaskCommandException("DISPATCH_SCHEMA_UNSUPPORTED: 调度快照版本不受支持");
        }
        if (intent.getRequestJson() == null || intent.getRequestJson().isBlank()
                || intent.getRequestHash() == null
                || !intent.getRequestHash().equals(sha256(intent.getRequestJson()))) {
            throw new TaskCommandException("DISPATCH_REQUEST_CORRUPTED: 调度请求摘要不一致");
        }
        try {
            return objectMapper.readValue(intent.getRequestJson(), DefenseTaskRequest.class);
        } catch (Exception exception) {
            throw new TaskCommandException("DISPATCH_SNAPSHOT_INVALID: 调度请求快照无法解析");
        }
    }

    private String writeRequest(DefenseTaskRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception exception) {
            throw new TaskCommandException("DISPATCH_SNAPSHOT_INVALID: 调度请求快照无法保存");
        }
    }

    private String sha256(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算调度请求摘要", exception);
        }
    }
}
