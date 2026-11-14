package com.securitymodel.infrastructure.observability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.securitymodel.application.task.GpuLeaseService;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime metrics backed by the database and the actual executor pool.
 *
 * <p>Cardinality is bounded: status, slot state and pool state are the only tags. Task id,
 * run id, user id and free-form error messages are never used as metric tags.</p>
 */
@Component
public class TaskRuntimeMetrics {
    private static final Logger log = LoggerFactory.getLogger(TaskRuntimeMetrics.class);
    private static final List<String> STATUSES = List.of("pending", "running", "success", "failed", "canceled");
    private static final List<String> SLOT_STATES = List.of("idle", "leased", "blocked", "unknown");
    private static final String DEMO_ORIGIN = "demo";

    private final PreDefenseTaskMapper taskMapper;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final GpuLeaseService gpuLeaseService;
    private final long staleAfterSeconds;

    private final Map<String, AtomicLong> taskCounts = new LinkedHashMap<>();
    private final AtomicLong staleRunning = new AtomicLong();
    private final Map<String, AtomicLong> poolCounts = new LinkedHashMap<>();
    private final Map<String, AtomicLong> slotCounts = new LinkedHashMap<>();
    private final AtomicLong refreshSuccess = new AtomicLong();
    private final AtomicLong lastRefreshEpoch = new AtomicLong();
    private final Counter recoveryCompleted;
    private final Counter recoveryFailed;
    private final Map<String, Timer> executionTimers = new LinkedHashMap<>();

    public TaskRuntimeMetrics(
            MeterRegistry registry,
            PreDefenseTaskMapper taskMapper,
            @Qualifier("taskAsync") ThreadPoolTaskExecutor taskExecutor,
            GpuLeaseService gpuLeaseService,
            @Value("${security-model.observability.stale-progress-seconds:600}") long staleAfterSeconds) {
        this.taskMapper = taskMapper;
        this.taskExecutor = taskExecutor;
        this.gpuLeaseService = gpuLeaseService;
        this.staleAfterSeconds = staleAfterSeconds;

        for (String status : STATUSES) {
            AtomicLong value = new AtomicLong();
            taskCounts.put(status, value);
            Gauge.builder("security_model_task_count", value, item -> (double) item.get())
                    .description("Current non-demo task count by public status")
                    .tag("status", status)
                    .register(registry);
        }
        Gauge.builder("security_model_stale_running_tasks", staleRunning, item -> (double) item.get())
                .description("Running tasks whose update_time is older than the stale threshold")
                .register(registry);
        for (String state : List.of("active", "queued", "completed")) {
            AtomicLong value = new AtomicLong();
            poolCounts.put(state, value);
            Gauge.builder("security_model_executor_tasks", value, item -> (double) item.get())
                    .description("Executor pool snapshot without task or user tags")
                    .tag("state", state)
                    .register(registry);
        }
        for (String state : SLOT_STATES) {
            AtomicLong value = new AtomicLong();
            slotCounts.put(state, value);
            Gauge.builder("security_model_gpu_slot", value, item -> (double) item.get())
                    .description("Single-GPU slot state; exactly one state is 1")
                    .tag("state", state)
                    .register(registry);
        }
        Gauge.builder("security_model_metrics_refresh_success", refreshSuccess, item -> (double) item.get())
                .description("1 when the last scheduled database refresh succeeded")
                .register(registry);
        Gauge.builder("security_model_metrics_last_refresh_epoch_seconds", lastRefreshEpoch,
                        item -> (double) item.get())
                .description("Unix epoch seconds of the last scheduled refresh attempt")
                .register(registry);
        recoveryCompleted = Counter.builder("security_model_recovery_total")
                .description("Startup dispatch recovery outcomes")
                .tag("result", "completed")
                .register(registry);
        recoveryFailed = Counter.builder("security_model_recovery_total")
                .description("Startup dispatch recovery outcomes")
                .tag("result", "failed")
                .register(registry);
        for (String outcome : List.of("success", "failed", "canceled", "interrupted", "unknown")) {
            executionTimers.put(outcome, Timer.builder("security_model_task_execution_duration")
                    .description("Wall-clock executor duration; outcome is a bounded status tag")
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    /** Refresh is bounded by a single aggregate query per status plus a small slot query. */
    @Scheduled(fixedDelayString = "${security-model.observability.refresh-interval-ms:30000}",
            initialDelayString = "${security-model.observability.refresh-initial-delay-ms:30000}")
    public void refresh() {
        lastRefreshEpoch.set(Instant.now().getEpochSecond());
        try {
            for (String status : STATUSES) {
                taskCounts.get(status).set(taskMapper.selectCount(Wrappers.<PreDefenseTask>lambdaQuery()
                        .eq(PreDefenseTask::getStatus, status)
                        .ne(PreDefenseTask::getDataOrigin, DEMO_ORIGIN)));
            }
            LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(staleAfterSeconds);
            staleRunning.set(taskMapper.selectCount(Wrappers.<PreDefenseTask>lambdaQuery()
                    .eq(PreDefenseTask::getStatus, "running")
                    .ne(PreDefenseTask::getDataOrigin, DEMO_ORIGIN)
                    .lt(PreDefenseTask::getUpdateTime, staleBefore)));
            refreshExecutorPool();
            refreshSlot();
            refreshSuccess.set(1);
        } catch (RuntimeException exception) {
            refreshSuccess.set(0);
            // Keep the last good values; a metric endpoint must not fail because MySQL is briefly down.
            log.warn("Runtime metrics refresh skipped: {}", exception.getMessage());
        }
    }

    private void refreshExecutorPool() {
        var executor = taskExecutor.getThreadPoolExecutor();
        poolCounts.get("active").set(executor.getActiveCount());
        poolCounts.get("queued").set(executor.getQueue().size());
        poolCounts.get("completed").set(executor.getCompletedTaskCount());
    }

    private void refreshSlot() {
        for (AtomicLong value : slotCounts.values()) {
            value.set(0);
        }
        try {
            if (!gpuLeaseService.findBlocked().isEmpty()) {
                slotCounts.get("blocked").set(1);
            } else if (!gpuLeaseService.findActive().isEmpty()) {
                slotCounts.get("leased").set(1);
            } else {
                slotCounts.get("idle").set(1);
            }
        } catch (RuntimeException exception) {
            slotCounts.get("unknown").set(1);
        }
    }

    public void recordRecovery(boolean completed) {
        if (completed) {
            recoveryCompleted.increment();
        } else {
            recoveryFailed.increment();
        }
    }

    /** Records one executor attempt; outcome is success/failed/canceled and never a task id. */
    public void recordExecution(String outcome, long durationNanos) {
        Timer timer = executionTimers.getOrDefault(outcome, executionTimers.get("unknown"));
        timer.record(Duration.ofNanos(Math.max(0, durationNanos)));
    }
}
