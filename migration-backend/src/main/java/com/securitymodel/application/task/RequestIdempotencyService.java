package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.task.RequestIdempotencyRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Request-level idempotency for create/start/retry. The key is scoped to the stable owner
 * user, so two users may use the same key without colliding. Same key + same body returns
 * the stored response; same key + different body or an in-flight request returns 409.
 */
@Service
public class RequestIdempotencyService {

    private final IdempotencyRecordStore store;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public RequestIdempotencyService(IdempotencyRecordStore store,
                                     CurrentUserService currentUserService,
                                     ObjectMapper objectMapper) {
        this.store = store;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public IdempotencyResult execute(String operation, String key, Object request,
                                     Long resourceId, Supplier<IdempotencyResult> action) {
        if (key == null) {
            return action.get();
        }
        if (!StringUtils.hasText(key) || key.length() > 128) {
            throw new TaskCommandException("IDEMPOTENCY_KEY_INVALID: 幂等键长度必须为 1-128 个字符");
        }
        Long ownerUserId = currentUserService.requireStableIdentity().userId();
        String requestHash = sha256(writeBody(request));
        LocalDateTime now = LocalDateTime.now();

        RequestIdempotencyRecord record = store.find(ownerUserId, operation, key);
        boolean claimed = false;
        if (record == null) {
            try {
                store.insertNew(ownerUserId, operation, key, requestHash, resourceId, now);
                claimed = true;
            } catch (DuplicateKeyException duplicate) {
                // Another caller won the unique-key insert; observe its state below.
            }
            record = store.find(ownerUserId, operation, key);
        }
        if (record == null) {
            throw new IdempotencyConflictException("REQUEST_IN_PROGRESS",
                    "相同幂等键的请求正在处理中，请稍后重试");
        }
        if (!requestHash.equals(record.getRequestHash())) {
            throw new IdempotencyConflictException("IDEMPOTENCY_KEY_REUSED",
                    "相同幂等键对应了不同的请求内容");
        }
        if (RequestIdempotencyRecord.SUCCEEDED.equals(record.getStatus())) {
            try {
                return new IdempotencyResult(record.getResponseCode(),
                        objectMapper.readTree(record.getResponseBody()));
            } catch (Exception exception) {
                throw new IdempotencyConflictException("IDEMPOTENCY_RESPONSE_INVALID",
                        "幂等响应记录无法读取");
            }
        }
        if (RequestIdempotencyRecord.PROCESSING.equals(record.getStatus()) && claimed) {
            return complete(record, resourceId, action);
        }
        if (RequestIdempotencyRecord.PROCESSING.equals(record.getStatus())) {
            throw new IdempotencyConflictException("REQUEST_IN_PROGRESS",
                    "相同幂等键的请求正在处理中，请稍后重试");
        }
        throw new IdempotencyConflictException("REQUEST_IN_PROGRESS",
                "相同幂等键的请求状态未知，请稍后重试");
    }

    private IdempotencyResult complete(RequestIdempotencyRecord record, Long resourceId,
                                       Supplier<IdempotencyResult> action) {
        try {
            IdempotencyResult result = action.get();
            String body = objectMapper.writeValueAsString(result.body());
            store.markSucceeded(record.getRecordId(), result.status(), body, resourceId,
                    LocalDateTime.now());
            return result;
        } catch (RuntimeException exception) {
            store.delete(record.getRecordId());
            throw exception;
        } catch (Exception exception) {
            store.delete(record.getRecordId());
            throw new TaskCommandException("IDEMPOTENCY_RESPONSE_WRITE_FAILED: 幂等响应无法保存");
        }
    }

    private String writeBody(Object request) {
        try {
            return request == null ? "" : objectMapper.writeValueAsString(request);
        } catch (Exception exception) {
            throw new TaskCommandException("IDEMPOTENCY_REQUEST_INVALID: 请求内容无法序列化");
        }
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
            throw new IllegalStateException("无法计算请求摘要", exception);
        }
    }
}
