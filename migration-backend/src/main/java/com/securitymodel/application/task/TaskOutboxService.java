package com.securitymodel.application.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.task.DispatchOutbox;
import com.securitymodel.domain.task.TaskDispatchIntent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Outbox persistence and claim handling. Broker I/O never happens inside these
 * transaction methods; the relay claims in one short transaction, publishes outside,
 * then writes the conditional result in another transaction.
 */
@Service
public class TaskOutboxService {

    public static final String EVENT_TYPE = DispatchEvent.EVENT_TASK_DISPATCH;

    private final com.securitymodel.infrastructure.persistence.mapper.DispatchOutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public TaskOutboxService(
            com.securitymodel.infrastructure.persistence.mapper.DispatchOutboxMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** Called inside the command transaction; a rollback removes the outbox row too. */
    public DispatchOutbox createPending(DefenseTaskRequest request, TaskDispatchIntent intent,
                                       LocalDateTime now) {
        return createPending(request, intent, now, null);
    }

    public DispatchOutbox createPending(DefenseTaskRequest request, TaskDispatchIntent intent,
                                       LocalDateTime now, Long ownerUserId) {
        DispatchOutbox event = buildEvent(request, intent, now, ownerUserId);
        try {
            mapper.insert(event);
            return event;
        } catch (DuplicateKeyException duplicate) {
            DispatchOutbox existing = mapper.findByRun(request.getTaskId(), request.getRunId(),
                    request.getAttempt(), EVENT_TYPE);
            if (existing == null) {
                throw duplicate;
            }
            return existing;
        }
    }

    /**
     * Recovery path: an intent may exist without an outbox row only for legacy/crash
     * anomalies. Rebuild the same event deterministically by run/attempt.
     */
    @Transactional(rollbackFor = Exception.class)
    public DispatchOutbox ensurePending(DefenseTaskRequest request, TaskDispatchIntent intent,
                                        LocalDateTime now, Long ownerUserId) {
        DispatchOutbox existing = mapper.findByRun(request.getTaskId(), request.getRunId(),
                request.getAttempt(), EVENT_TYPE);
        return existing != null ? existing : createPending(request, intent, now, ownerUserId);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<DispatchOutbox> claimBatch(LocalDateTime now, String owner, int limit,
                                           int claimSeconds) {
        List<DispatchOutbox> candidates = mapper.findClaimCandidates(now, limit);
        List<DispatchOutbox> claimed = new ArrayList<>();
        for (DispatchOutbox candidate : candidates) {
            String token = UUID.randomUUID().toString();
            int updated = mapper.markPublishing(candidate.getOutboxId(), owner, token,
                    now.plusSeconds(claimSeconds), now);
            if (updated == 1) {
                candidate.setStatus(DispatchOutbox.PUBLISHING);
                candidate.setClaimOwner(owner);
                candidate.setClaimToken(token);
                candidate.setClaimUntil(now.plusSeconds(claimSeconds));
                candidate.setPublishAttempts((candidate.getPublishAttempts() == null ? 0
                        : candidate.getPublishAttempts()) + 1);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    @Transactional(rollbackFor = Exception.class)
    public int reclaimExpired(LocalDateTime now) {
        return mapper.reclaimExpired(now);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markPublished(DispatchOutbox outbox, LocalDateTime now) {
        return mapper.markPublished(outbox.getOutboxId(), outbox.getClaimToken(), now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markRetry(DispatchOutbox outbox, String error, LocalDateTime nextAt,
                             LocalDateTime now) {
        return mapper.markRetry(outbox.getOutboxId(), outbox.getClaimToken(), nextAt,
                truncate(error, 255), now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markDead(DispatchOutbox outbox, String error, LocalDateTime now) {
        return mapper.markDead(outbox.getOutboxId(), outbox.getClaimToken(),
                truncate(error, 255), now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public int markDeadByMessageId(String messageId, String error, LocalDateTime now) {
        return mapper.markDeadByMessageId(messageId, truncate(error, 255), now);
    }

    public List<DispatchOutbox> findDead(int limit) {
        return mapper.findDead(limit);
    }

    public DispatchOutbox findByMessageId(String messageId) {
        return mapper.findByMessageId(messageId);
    }

    /**
     * Replays a dead row only when the caller has already proven the task/intent is still
     * pending and unexecuted. A fresh messageId is used so the old consumption-ledger row
     * cannot turn the replay into a duplicate ACK.
     */
    @Transactional(rollbackFor = Exception.class)
    public DispatchOutbox replayDead(String messageId, LocalDateTime now) {
        DispatchOutbox existing = mapper.findByMessageIdForUpdate(messageId);
        if (existing == null || !DispatchOutbox.DEAD.equals(existing.getStatus())) {
            return null;
        }
        DispatchEvent oldEvent = readEvent(existing.getPayloadJson());
        String newMessageId = UUID.randomUUID().toString();
        DispatchEvent replacement = new DispatchEvent(oldEvent.schemaVersion(), newMessageId,
                oldEvent.eventType(), oldEvent.taskId(), oldEvent.runId(), oldEvent.attempt(),
                oldEvent.ownerUserId(), oldEvent.intentId(), oldEvent.requestHash(), now);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(replacement);
        } catch (JsonProcessingException exception) {
            throw new TaskCommandException("DISPATCH_EVENT_SERIALIZE_FAILED: 调度事件无法序列化: "
                    + exception.getMessage());
        }
        if (mapper.replayDead(messageId, newMessageId, payloadJson, sha256(payloadJson), now) != 1) {
            return null;
        }
        existing.setMessageId(newMessageId);
        existing.setPayloadJson(payloadJson);
        existing.setPayloadHash(sha256(payloadJson));
        existing.setStatus(DispatchOutbox.NEW);
        existing.setPublishAttempts(0);
        existing.setNextAt(now);
        existing.setClaimOwner(null);
        existing.setClaimToken(null);
        existing.setClaimUntil(null);
        existing.setLastError(null);
        existing.setPublishedAt(null);
        return existing;
    }

    public long countByStatus(String status) {
        return mapper.countByStatus(status);
    }

    public long countBacklog(LocalDateTime now) {
        return mapper.countBacklog(now);
    }

    public LocalDateTime oldestUnpublishedAt() {
        return mapper.oldestUnpublishedAt();
    }

    public DispatchEvent readEvent(String payloadJson) {
        try {
            DispatchEvent event = objectMapper.readValue(payloadJson, DispatchEvent.class);
            if (event.schemaVersion() == null || event.schemaVersion() != DispatchEvent.SCHEMA_VERSION) {
                throw new TaskCommandException("DISPATCH_SCHEMA_UNSUPPORTED: 调度事件版本不受支持");
            }
            if (event.messageId() == null || event.taskId() == null || event.runId() == null
                    || event.attempt() == null || event.eventType() == null) {
                throw new TaskCommandException("DISPATCH_EVENT_INVALID: 调度事件字段不完整");
            }
            if (!EVENT_TYPE.equals(event.eventType())) {
                throw new TaskCommandException("DISPATCH_EVENT_INVALID: 调度事件类型不受支持");
            }
            return event;
        } catch (TaskCommandException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TaskCommandException("DISPATCH_EVENT_INVALID: 调度事件无法解析");
        }
    }

    private DispatchOutbox buildEvent(DefenseTaskRequest request, TaskDispatchIntent intent,
                                      LocalDateTime now, Long ownerUserId) {
        DispatchEvent event = new DispatchEvent(DispatchEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(), EVENT_TYPE, request.getTaskId(), request.getRunId(),
                request.getAttempt(), ownerUserId, intent.getIntentId(),
                intent.getRequestHash(), now);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new TaskCommandException("DISPATCH_EVENT_INVALID: 调度事件无法保存");
        }
        DispatchOutbox outbox = new DispatchOutbox();
        outbox.setMessageId(event.messageId());
        outbox.setEventType(EVENT_TYPE);
        outbox.setTaskId(request.getTaskId());
        outbox.setRunId(request.getRunId());
        outbox.setAttempt(request.getAttempt());
        outbox.setIntentId(intent.getIntentId());
        outbox.setPayloadHash(sha256(payload));
        outbox.setPayloadJson(payload);
        outbox.setStatus(DispatchOutbox.NEW);
        outbox.setPublishAttempts(0);
        outbox.setNextAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算调度事件摘要", exception);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
