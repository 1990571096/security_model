package com.securitymodel.application.task;

import com.securitymodel.domain.task.DispatchConsumption;
import com.securitymodel.infrastructure.persistence.mapper.DispatchConsumptionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class TaskDispatchConsumptionService {

    public enum ClaimState { CLAIMED, DUPLICATE, IN_PROGRESS, STALE }

    public record Claim(ClaimState state, String claimToken, DispatchConsumption existing) {
    }

    private final DispatchConsumptionMapper mapper;

    public TaskDispatchConsumptionService(DispatchConsumptionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Claim claim(DispatchEvent event, String workerId, int claimSeconds, LocalDateTime now) {
        String token = UUID.randomUUID().toString();
        try {
            mapper.insertProcessing(event.messageId(), event.taskId(), event.runId(),
                    event.attempt(), workerId, workerId, now.plusSeconds(claimSeconds), token, now);
            return new Claim(ClaimState.CLAIMED, token, null);
        } catch (DuplicateKeyException duplicate) {
            DispatchConsumption existing = mapper.findByMessageId(event.messageId());
            if (existing == null) {
                throw duplicate;
            }
            if (DispatchConsumption.TERMINAL.equals(existing.getStatus())) {
                return new Claim(ClaimState.DUPLICATE, null, existing);
            }
            if (!DispatchConsumption.RETRY.equals(existing.getStatus())
                    && existing.getClaimUntil() != null && existing.getClaimUntil().isAfter(now)) {
                return new Claim(ClaimState.IN_PROGRESS, null, existing);
            }
            int reclaimed = mapper.reclaimExpired(event.messageId(), workerId, workerId,
                    now.plusSeconds(claimSeconds), token, now);
            if (reclaimed == 1) {
                return new Claim(ClaimState.CLAIMED, token, existing);
            }
            return new Claim(ClaimState.IN_PROGRESS, null, existing);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markTerminal(String messageId, String claimToken, String outcome,
                                String errorCode, LocalDateTime now) {
        return mapper.markTerminal(messageId, claimToken, outcome, errorCode, now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean releaseForRetry(String messageId, String claimToken, String errorCode,
                                   LocalDateTime now) {
        return mapper.releaseForRetry(messageId, claimToken, errorCode, now) == 1;
    }

    public long countExpiredProcessing(LocalDateTime now) {
        return mapper.countExpiredProcessing(now);
    }

    public long countByStatus(String status) {
        return mapper.countByStatus(status);
    }

    public LocalDateTime oldestProcessingAt() {
        return mapper.oldestProcessingAt();
    }

    /** A non-terminal ledger row means a Worker has at least claimed this attempt. */
    public boolean hasUnfinishedOwnership(Long taskId, String runId, Integer attempt) {
        return mapper.findUnfinishedByRun(taskId, runId, attempt) != null;
    }
}
