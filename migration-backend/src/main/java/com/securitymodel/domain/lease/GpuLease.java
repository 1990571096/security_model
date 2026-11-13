package com.securitymodel.domain.lease;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row of {@code sys_gpu_lease}: the single physical GPU slot on this machine.
 *
 * <p>The row is reused for every acquisition; {@code fencingToken} increases monotonically
 * so a stale worker can never renew or release a lease that now belongs to another run.</p>
 */
@Data
public class GpuLease {
    private String slotId;
    private String leaseToken;
    private Long taskId;
    private String runId;
    private Integer attempt;
    private Long fencingToken;
    private LocalDateTime acquiredAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime expiresAt;
    private LocalDateTime releasedAt;
}
