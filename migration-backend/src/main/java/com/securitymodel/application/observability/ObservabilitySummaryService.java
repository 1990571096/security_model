package com.securitymodel.application.observability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.task.GpuLeaseService;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only operational summary. It intentionally returns aggregate counts and slot state
 * only: no task ids, run ids, paths, usernames or per-user counts.
 */
@Service
public class ObservabilitySummaryService {
    private static final String DEMO_ORIGIN = "demo";

    private final CurrentUserService currentUserService;
    private final PreDefenseTaskMapper taskMapper;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final GpuLeaseService gpuLeaseService;
    private final MeterRegistry meterRegistry;
    private final long staleAfterSeconds;

    public ObservabilitySummaryService(
            CurrentUserService currentUserService,
            PreDefenseTaskMapper taskMapper,
            @Qualifier("taskAsync") ThreadPoolTaskExecutor taskExecutor,
            GpuLeaseService gpuLeaseService,
            MeterRegistry meterRegistry,
            @Value("${security-model.observability.stale-progress-seconds:600}") long staleAfterSeconds) {
        this.currentUserService = currentUserService;
        this.taskMapper = taskMapper;
        this.taskExecutor = taskExecutor;
        this.gpuLeaseService = gpuLeaseService;
        this.meterRegistry = meterRegistry;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    public Map<String, Object> summary() {
        currentUserService.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "all-users");
        result.put("taskCounts", taskCounts());
        result.put("staleRunningTasks", staleRunningTasks());
        result.put("executor", executor());
        result.put("gpuSlot", gpuSlot());
        result.put("cache", cache());
        result.put("collectedAt", LocalDateTime.now().toString());
        return result;
    }

    private Map<String, Object> taskCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : List.of("pending", "running", "success", "failed", "canceled")) {
            counts.put(status, taskMapper.selectCount(Wrappers.<PreDefenseTask>lambdaQuery()
                    .eq(PreDefenseTask::getStatus, status)
                    .ne(PreDefenseTask::getDataOrigin, DEMO_ORIGIN)));
        }
        return counts;
    }

    private long staleRunningTasks() {
        return taskMapper.selectCount(Wrappers.<PreDefenseTask>lambdaQuery()
                .eq(PreDefenseTask::getStatus, "running")
                .ne(PreDefenseTask::getDataOrigin, DEMO_ORIGIN)
                .lt(PreDefenseTask::getUpdateTime, LocalDateTime.now().minusSeconds(staleAfterSeconds)));
    }

    private Map<String, Object> executor() {
        var executor = taskExecutor.getThreadPoolExecutor();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", executor.getActiveCount());
        result.put("queued", executor.getQueue().size());
        result.put("poolSize", executor.getPoolSize());
        result.put("maxPoolSize", executor.getMaximumPoolSize());
        result.put("completed", executor.getCompletedTaskCount());
        result.put("acceptingTasks", !executor.isShutdown() && !executor.isTerminated());
        return result;
    }

    private Map<String, Object> gpuSlot() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("slotId", gpuLeaseService.getSlotId());
        try {
            if (!gpuLeaseService.findBlocked().isEmpty()) {
                slot.put("state", "blocked");
                slot.put("blockedAt", gpuLeaseService.findBlocked().get(0).getBlockedAt());
            } else if (!gpuLeaseService.findActive().isEmpty()) {
                slot.put("state", "leased");
            } else {
                slot.put("state", "idle");
            }
        } catch (RuntimeException exception) {
            slot.put("state", "unknown");
            slot.put("message", "GPU 槽位状态暂不可读");
        }
        return slot;
    }

    private Map<String, Object> cache() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hit", counter("security_model_cache_requests", "result", "hit"));
        result.put("miss", counter("security_model_cache_requests", "result", "miss"));
        result.put("degraded", counter("security_model_cache_requests", "result", "degraded"));
        result.put("staleFillSkipped", counter("security_model_cache_stale_fill_skipped_total", null, null));
        return result;
    }

    private double counter(String name, String tagKey, String tagValue) {
        var counter = tagKey == null
                ? meterRegistry.find(name).counter()
                : meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : ((Counter) counter).count();
    }
}
