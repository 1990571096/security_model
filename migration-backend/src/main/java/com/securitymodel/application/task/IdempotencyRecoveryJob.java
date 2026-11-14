package com.securitymodel.application.task;

import com.securitymodel.domain.task.RequestIdempotencyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Converts abandoned "processing" rows into an explicit reconciliation state. It never
 * deletes a processing row: a crash may have happened after the business write committed,
 * so only a human/operator can decide whether a retry is safe.
 */
@Component
public class IdempotencyRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecoveryJob.class);

    private final IdempotencyRecordStore store;
    private final long staleSeconds;
    private final int batchSize;

    public IdempotencyRecoveryJob(
            IdempotencyRecordStore store,
            @Value("${security-model.idempotency.stale-processing-seconds:3600}") long staleSeconds,
            @Value("${security-model.idempotency.recovery-batch-size:20}") int batchSize) {
        this.store = store;
        this.staleSeconds = staleSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${security-model.idempotency.recovery-interval-ms:60000}",
            initialDelayString = "${security-model.idempotency.recovery-initial-delay-ms:60000}")
    public void recoverStaleProcessing() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleSeconds);
            List<RequestIdempotencyRecord> stale = store.findStaleProcessing(cutoff, batchSize);
            for (RequestIdempotencyRecord record : stale) {
                store.markStaleProcessingFailed(record.getRecordId(), LocalDateTime.now());
                log.warn("幂等记录处理中状态超时，已转为待人工核对: recordId={}, operation={}",
                        record.getRecordId(), record.getOperation());
            }
        } catch (Exception exception) {
            log.warn("幂等记录恢复扫描失败: {}", exception.getMessage());
        }
    }
}
