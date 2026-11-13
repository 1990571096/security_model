package com.securitymodel.application.task;

import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.persistence.mapper.GpuLeaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MySQL-backed single-GPU lease. The database row is the only authority: a JVM
 * {@code synchronized} block or a file lock cannot survive {@code kill -9} and cannot
 * coordinate two processes.
 *
 * <p>Fencing: each successful acquisition increments {@code fencing_token}. Renew and
 * release are conditional on the exact {@code lease_token}, so a worker whose lease was
 * reclaimed can neither extend nor release the new owner's lease.</p>
 */
@Service
public class GpuLeaseService {

    private static final Logger log = LoggerFactory.getLogger(GpuLeaseService.class);

    private final GpuLeaseMapper mapper;
    private final String slotId;
    private final long ttlSeconds;
    /** Tokens this JVM actually owns; the heartbeat must never keep a dead worker's lease alive. */
    private final Set<String> localTokens = ConcurrentHashMap.newKeySet();

    public GpuLeaseService(
            GpuLeaseMapper mapper,
            @Value("${security-model.executor.gpu.slot-id:default-gpu-0}") String slotId,
            @Value("${security-model.executor.gpu.lease-ttl-seconds:300}") long ttlSeconds) {
        this.mapper = mapper;
        this.slotId = slotId;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Must be called inside the same transaction as the task claim. Either the conditional
     * UPDATE wins the slot, or the caller gets {@code GPU_BUSY} and the transaction rolls
     * back without leaving a half-claimed lease.
     */
    public GpuLease acquire(Long taskId, String runId, Integer attempt) {
        if (!StringUtils.hasText(runId)) {
            throw new TaskCommandException("GPU_LEASE_INVALID: 缺少运行标识，无法申请 GPU 租约");
        }
        mapper.bootstrap(slotId);
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        int updated = mapper.acquire(slotId, token, taskId, runId, attempt, now, now.plusSeconds(ttlSeconds));
        if (updated != 1) {
            throw new TaskCommandException("GPU_BUSY: 当前 GPU 正被其他任务占用，请稍后重试");
        }
        GpuLease lease = mapper.findBySlot(slotId);
        if (lease == null || !token.equals(lease.getLeaseToken())) {
            throw new TaskCommandException("GPU_LEASE_UNAVAILABLE: GPU 租约申请结果无法确认");
        }
        localTokens.add(token);
        return lease;
    }

    public boolean renew(GpuLease lease) {
        if (lease == null || !StringUtils.hasText(lease.getLeaseToken())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return mapper.renew(slotId, lease.getLeaseToken(), now, now.plusSeconds(ttlSeconds)) == 1;
    }

    public void release(GpuLease lease) {
        if (lease == null || !StringUtils.hasText(lease.getLeaseToken())) {
            return;
        }
        mapper.release(slotId, lease.getLeaseToken(), LocalDateTime.now());
        localTokens.remove(lease.getLeaseToken());
    }

    /** Best-effort release used from executor {@code finally}; never throws into the task path. */
    public void releaseQuietly(String leaseToken) {
        if (!StringUtils.hasText(leaseToken)) {
            return;
        }
        try {
            mapper.release(slotId, leaseToken, LocalDateTime.now());
        } catch (Exception exception) {
            log.warn("GPU 租约释放失败，将等待过期回收: slot={}, reason={}", slotId, exception.getMessage());
        } finally {
            localTokens.remove(leaseToken);
        }
    }

    /** Recovery path: the executing JVM is gone, so release by the recorded run identity. */
    public void releaseByRun(Long taskId, String runId) {
        if (taskId == null || !StringUtils.hasText(runId)) {
            return;
        }
        mapper.releaseByRun(slotId, taskId, runId, LocalDateTime.now());
    }

    public List<GpuLease> findActive() {
        return mapper.findActive(LocalDateTime.now());
    }

    /**
     * Only leases whose token lives in this JVM. Renewing every active row would resurrect
     * a crashed worker's lease and break expiry reclaim.
     */
    public List<GpuLease> findOwnedActive() {
        return mapper.findActive(LocalDateTime.now()).stream()
                .filter(lease -> lease.getLeaseToken() != null && localTokens.contains(lease.getLeaseToken()))
                .collect(Collectors.toList());
    }

    public String getSlotId() {
        return slotId;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
