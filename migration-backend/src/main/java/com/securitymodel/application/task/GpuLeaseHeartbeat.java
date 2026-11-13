package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Renews the lease of tasks that are still executing in this JVM. If the row no longer
 * belongs to us (expired and reclaimed by another start), the local Python process is
 * canceled and the task is failed explicitly instead of continuing on an unleased GPU.
 */
@Component
public class GpuLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(GpuLeaseHeartbeat.class);

    private final GpuLeaseService leaseService;
    private final PreDefenseTaskMapper taskMapper;
    private final com.securitymodel.infrastructure.executor.DefenseTaskExecutor executor;
    private final ObjectMapper objectMapper;

    public GpuLeaseHeartbeat(
            GpuLeaseService leaseService,
            PreDefenseTaskMapper taskMapper,
            com.securitymodel.infrastructure.executor.DefenseTaskExecutor executor,
            ObjectMapper objectMapper) {
        this.leaseService = leaseService;
        this.taskMapper = taskMapper;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${security-model.executor.gpu.heartbeat-interval-ms:60000}")
    public void renewActiveLeases() {
        try {
            for (GpuLease lease : leaseService.findOwnedActive()) {
                if (!leaseService.renew(lease)) {
                    log.error("GPU 租约已失效，取消本地执行: slot={}, taskId={}, runId={}",
                            lease.getSlotId(), lease.getTaskId(), lease.getRunId());
                    failLeaseLost(lease);
                }
            }
        } catch (Exception exception) {
            // A missing table or a transient database error must not kill the scheduler.
            log.warn("GPU 租约续期扫描失败: {}", exception.getMessage());
        }
    }

    private void failLeaseLost(GpuLease lease) {
        if (lease.getTaskId() == null || lease.getAttempt() == null || lease.getRunId() == null) {
            return;
        }
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("executor", "security-model-platform");
            result.put("stage", "pre_defense");
            result.put("status", "failed");
            result.put("message", "GPU 租约已过期并被回收");
            result.put("run_id", lease.getRunId());
            result.put("attempt", lease.getAttempt());
            result.put("error_code", "GPU_LEASE_LOST");
            result.put("failure_reason", "GPU 租约续期失败，执行已取消");
            result.put("finished_at", LocalDateTime.now().toString());
            result.set("artifacts", objectMapper.createObjectNode());
            taskMapper.failBeforeExecution(lease.getTaskId(), lease.getRunId(), lease.getAttempt(),
                    result.toString(), LocalDateTime.now());
            executor.cancelPreDefense(lease.getTaskId(), lease.getRunId());
        } catch (Exception exception) {
            log.warn("GPU 租约丢失后的任务收敛失败: taskId={}, reason={}",
                    lease.getTaskId(), exception.getMessage());
        }
    }
}
