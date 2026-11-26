package com.securitymodel.application.task;

import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.domain.task.TaskDispatchIntent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * One short transaction owns both the {@code pending -> submitting} intent update and the
 * GPU lease acquisition. Any failure rolls both back, so a Worker crash cannot leave a
 * submitting intent without a lease or vice versa.
 */
@Service
public class DispatchClaimTransactionService {

    public record ClaimOutcome(boolean claimed, GpuLease lease) {
    }

    private final TaskDispatchIntentService intentService;
    private final GpuLeaseService gpuLeaseService;

    public DispatchClaimTransactionService(TaskDispatchIntentService intentService,
                                           GpuLeaseService gpuLeaseService) {
        this.intentService = intentService;
        this.gpuLeaseService = gpuLeaseService;
    }

    @Transactional(rollbackFor = Exception.class)
    public ClaimOutcome claim(TaskDispatchIntent intent, LocalDateTime now) {
        if (!intentService.markSubmitting(intent.getTaskId(), intent.getRunId(),
                intent.getAttempt(), now)) {
            return new ClaimOutcome(false, null);
        }
        GpuLease lease = gpuLeaseService.acquire(intent.getTaskId(), intent.getRunId(),
                intent.getAttempt());
        return new ClaimOutcome(true, lease);
    }
}
