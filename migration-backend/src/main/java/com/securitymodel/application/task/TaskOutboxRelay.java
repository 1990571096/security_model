package com.securitymodel.application.task;

import com.securitymodel.domain.task.DispatchOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API-process relay. It claims rows in a short transaction, publishes outside the
 * transaction, then conditionally records the result. A crash leaves a {@code publishing}
 * row that is reclaimed after its claim lease expires; an old confirm cannot overwrite the
 * next claim because every write also matches {@code claim_token}.
 */
@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class TaskOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(TaskOutboxRelay.class);

    private final DispatchRuntimePolicy runtimePolicy;
    private final TaskOutboxService outboxService;
    private final DispatchTransport transport;
    private final TaskDispatchCoordinator dispatchCoordinator;
    private final String relayOwner = "relay-" + UUID.randomUUID();
    private final int batchSize;
    private final int claimSeconds;
    private final int maxAttempts;
    private final List<Long> retryDelaysMs = new ArrayList<>();

    public TaskOutboxRelay(
            DispatchRuntimePolicy runtimePolicy,
            TaskOutboxService outboxService,
            DispatchTransport transport,
            TaskDispatchCoordinator dispatchCoordinator,
            @Value("${security-model.dispatch.outbox.batch-size:20}") int batchSize,
            @Value("${security-model.dispatch.outbox.claim-seconds:60}") int claimSeconds,
            @Value("${security-model.dispatch.outbox.max-publish-attempts:3}") int maxAttempts,
            @Value("${security-model.dispatch.outbox.retry-delays-seconds:5,30,120}")
            String retryDelaysSeconds) {
        this.runtimePolicy = runtimePolicy;
        this.outboxService = outboxService;
        this.transport = transport;
        this.dispatchCoordinator = dispatchCoordinator;
        this.batchSize = batchSize;
        this.claimSeconds = claimSeconds;
        this.maxAttempts = maxAttempts;
        for (String value : retryDelaysSeconds.split(",")) {
            retryDelaysMs.add(Long.parseLong(value.trim()) * 1000L);
        }
    }

    @Scheduled(fixedDelayString = "${security-model.dispatch.outbox.relay-interval-ms:3000}",
            initialDelayString = "${security-model.dispatch.outbox.relay-interval-ms:3000}")
    public void scheduledRelay() {
        relayOnce();
    }

    /** Exposed for tests and a manual ops trigger; safe to call repeatedly. */
    public int relayOnce() {
        if (!runtimePolicy.runsRelay()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        outboxService.reclaimExpired(now);
        List<DispatchOutbox> claimed = outboxService.claimBatch(now, relayOwner, batchSize,
                claimSeconds);
        int published = 0;
        for (DispatchOutbox outbox : claimed) {
            DispatchEvent event = null;
            try {
                event = outboxService.readEvent(outbox.getPayloadJson());
                transport.publish(event, outbox.getPayloadJson());
                if (outboxService.markPublished(outbox, LocalDateTime.now())) {
                    published++;
                }
            } catch (Exception exception) {
                recordFailure(outbox, event, exception);
            }
        }
        return published;
    }

    private void recordFailure(DispatchOutbox outbox, DispatchEvent event, Exception exception) {
        LocalDateTime now = LocalDateTime.now();
        String reason = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        int attempts = outbox.getPublishAttempts() == null ? 1 : outbox.getPublishAttempts();
        if (reason.startsWith("RABBIT_UNROUTABLE") || attempts >= maxAttempts) {
            boolean unroutable = reason.startsWith("RABBIT_UNROUTABLE");
            if (outboxService.markDead(outbox, reason, now) && event != null && unroutable) {
                // A dispatch event that can never reach the worker must not leave a task
                // running forever with no consumer. Converge task, intent and outbox together
                // (each write is conditional on its own identity/attempt).
                dispatchCoordinator.failAndAbandon(event.taskId(), event.runId(), event.attempt(),
                        "DISPATCH_PUBLISH_FAILED", "Outbox 发布超过上限: " + reason);
            }
            // Transient publish exhaustion (broker down, confirm timeout) means the message
            // never reached the worker. Keep task/intent pending so an operator can replay
            // the dead row for the same attempt after repairing the broker; only unroutable
            // topology errors are terminal here.
            if (unroutable) {
                log.error("Outbox 不可路由，标记 dead: messageId={}, attempts={}, reason={}",
                        outbox.getMessageId(), attempts, reason);
            } else {
                log.error("Outbox 发布超过上限，标记 dead 并等待人工重放: messageId={}, attempts={}, reason={}",
                        outbox.getMessageId(), attempts, reason);
            }
            return;
        }
        long delay = retryDelaysMs.get(Math.min(attempts - 1, retryDelaysMs.size() - 1));
        outboxService.markRetry(outbox, reason, now.plusNanos(delay * 1_000_000L), now);
        log.warn("Outbox 发布失败，退避重试: messageId={}, attempts={}, delayMs={}, reason={}",
                outbox.getMessageId(), attempts, delay, reason);
    }
}
