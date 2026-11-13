package com.securitymodel.application.task;

import com.securitymodel.domain.task.RequestIdempotencyRecord;
import com.securitymodel.infrastructure.persistence.mapper.RequestIdempotencyMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Short transactions around the idempotency row. The claim commits before the business
 * transaction starts so a concurrent duplicate can observe "processing"; the success
 * record is updated inside the business transaction so both commit or roll back together.
 */
@Component
public class IdempotencyRecordStore {

    private final RequestIdempotencyMapper mapper;

    public IdempotencyRecordStore(RequestIdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertNew(Long ownerUserId, String operation, String key,
                          String requestHash, Long resourceId, LocalDateTime now) {
        mapper.deleteExpired(ownerUserId, operation, key, now);
        RequestIdempotencyRecord record = new RequestIdempotencyRecord();
        record.setOwnerUserId(ownerUserId);
        record.setOperation(operation);
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setResourceId(resourceId);
        record.setStatus(RequestIdempotencyRecord.PROCESSING);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setExpiresAt(now.plusHours(24));
        mapper.insert(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RequestIdempotencyRecord find(Long ownerUserId, String operation, String key) {
        return mapper.findByKey(ownerUserId, operation, key, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSucceeded(Long recordId, int responseCode, String responseBody, Long resourceId,
                              LocalDateTime now) {
        int updated = mapper.markSucceeded(recordId, responseCode, responseBody, resourceId, now);
        if (updated != 1) {
            throw new IllegalStateException("幂等成功记录无法写入");
        }
    }

    /** Releases a failed/rolled-back key; runs in its own transaction so it survives rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(Long recordId) {
        mapper.deleteById(recordId);
    }

    public static boolean isDuplicate(DuplicateKeyException exception) {
        return exception != null;
    }
}
