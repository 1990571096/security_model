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
    private final java.util.Map<String, GpuLease> localLeases = new ConcurrentHashMap<>();

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
            GpuLease current = mapper.findBySlot(slotId);
            if (current != null && current.getBlockedAt() != null) {
                throw new TaskCommandException("GPU_LEASE_BLOCKED: GPU 槽位因遗留进程未确认退出而被隔离("
                        + current.getBlockedReason() + ")，需确认进程退出后解除");
            }
            if (current != null && current.getLeaseToken() != null
                    && current.getExpiresAt() != null && current.getExpiresAt().isBefore(now)) {
                // TTL expiry proves only that heartbeats stopped, not that the GPU is idle.
                // Quarantine the slot until a process probe confirms the old tree is gone.
                mapper.block(slotId, current.getTaskId(), current.getRunId(),
                        "LEASE_EXPIRED", now);
                throw new TaskCommandException("GPU_LEASE_EXPIRED: GPU 租约已过期但进程未确认退出，槽位已隔离");
            }
            throw new TaskCommandException("GPU_BUSY: 当前 GPU 正被其他任务占用，请稍后重试");
        }
        GpuLease lease = mapper.findBySlot(slotId);
        if (lease == null || !token.equals(lease.getLeaseToken())) {
            throw new TaskCommandException("GPU_LEASE_UNAVAILABLE: GPU 租约申请结果无法确认");
        }
        localTokens.add(token);
        localLeases.put(token, lease);
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
        localLeases.remove(lease.getLeaseToken());
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
            localLeases.remove(leaseToken);
        }
    }

    /** Recovery path: the executing JVM is gone, so release by the recorded run identity. */
    public void releaseByRun(Long taskId, String runId) {
        if (taskId == null || !StringUtils.hasText(runId)) {
            return;
        }
        mapper.releaseByRun(slotId, taskId, runId, LocalDateTime.now());
        localLeases.entrySet().removeIf(entry -> taskId.equals(entry.getValue().getTaskId())
                && runId.equals(entry.getValue().getRunId()));
        localTokens.removeIf(token -> !localLeases.containsKey(token));
    }

    /** Quarantine the slot until an orphaned executor is proven gone; TTL alone is not proof. */
    public void markBlocked(Long taskId, String runId, String reason) {
        if (taskId == null || !StringUtils.hasText(runId)) {
            return;
        }
        mapper.block(slotId, taskId, runId, StringUtils.hasText(reason) ? reason : "ORPHANED_PROCESS_UNKNOWN",
                LocalDateTime.now());
    }

    /** Quarantined slots, re-probed by {@code GpuLeaseBlockedProbe}. */
    public List<GpuLease> findBlocked() {
        return mapper.findBlocked();
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

    /**
     * Local snapshot for the heartbeat. It deliberately does not query MySQL, so a database
     * outage still lets the heartbeat cancel a process whose lease can no longer be proven.
     */
    public List<GpuLease> findLocalLeases() {
        return List.copyOf(localLeases.values());
    }

    public String getSlotId() {
        return slotId;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
