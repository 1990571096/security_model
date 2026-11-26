package com.securitymodel.application.task;

import com.securitymodel.domain.task.DispatchConsumption;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Worker message policy. ACK is returned only after:
 * a terminal DB state + terminal consumption ledger, a duplicate/stale audit, or a
 * confirmed transfer to a retry queue / DLQ. Uncertain execution is ACKed with an
 * {@code uncertain} ledger so recovery, not a redelivery, owns the follow-up.
 */
@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class DispatchMessageHandler {

    public enum Action { ACK, NO_ACK, REJECT }

    private static final Logger log = LoggerFactory.getLogger(DispatchMessageHandler.class);

    private final DispatchRuntimePolicy runtimePolicy;
    private final TaskOutboxService outboxService;
    private final TaskDispatchConsumptionService consumptionService;
    private final TaskDispatchIntentService intentService;
    private final TaskDispatchCoordinator dispatchCoordinator;
    private final PreDefenseTaskMapper taskMapper;
    private final AttemptProcessRegistry processRegistry;
    private final DispatchTransport transport;
    private final String workerId;
    private final int claimSeconds;
    private final int maxRetries;

    public DispatchMessageHandler(
            DispatchRuntimePolicy runtimePolicy,
            TaskOutboxService outboxService,
            TaskDispatchConsumptionService consumptionService,
            TaskDispatchIntentService intentService,
            TaskDispatchCoordinator dispatchCoordinator,
            PreDefenseTaskMapper taskMapper,
            AttemptProcessRegistry processRegistry,
            DispatchTransport transport,
            @Value("${security-model.dispatch.worker.id:}") String configuredWorkerId,
            @Value("${security-model.dispatch.worker.consumption-claim-seconds:300}") int claimSeconds,
            @Value("${security-model.dispatch.worker.max-retries:3}") int maxRetries) {
        this.runtimePolicy = runtimePolicy;
        this.outboxService = outboxService;
        this.consumptionService = consumptionService;
        this.intentService = intentService;
        this.dispatchCoordinator = dispatchCoordinator;
        this.taskMapper = taskMapper;
        this.processRegistry = processRegistry;
        this.transport = transport;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "worker-" + UUID.randomUUID() : configuredWorkerId;
        this.claimSeconds = claimSeconds;
        this.maxRetries = maxRetries;
    }

    public Action handle(String payloadJson, int retryCount) {
        if (retryCount < 0) {
            log.warn("拒绝负数重试计数: retryCount={}", retryCount);
            return Action.REJECT;
        }
        if (!runtimePolicy.runsConsumer()) {
            return Action.NO_ACK;
        }
        DispatchEvent event;
        try {
            event = outboxService.readEvent(payloadJson);
        } catch (RuntimeException exception) {
            log.warn("拒绝非法调度事件: {}", exception.getMessage());
            return Action.REJECT;
        }
        if (processRegistry.isLocallyAccepted(event.taskId(), event.runId(), event.attempt())) {
            // The same JVM already owns a live execution for this attempt. ACK only this
            // duplicate delivery: do not claim, do not write the original ledger terminal,
            // and do not start a second Python. The original delivery still owns completion.
            log.info("本地执行仍持有 attempt，重复投递直接 ACK: messageId={}, taskId={}, runId={}, attempt={}",
                    event.messageId(), event.taskId(), event.runId(), event.attempt());
            return Action.ACK;
        }
        TaskDispatchConsumptionService.Claim claim;
        try {
            claim = consumptionService.claim(event, workerId, claimSeconds, LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("消费记录领取失败: messageId={}, reason={}", event.messageId(),
                    exception.getMessage());
            // A DB outage must not become an immediate basicNack requeue loop. Move the
            // message through the configured delayed retry queue and release the old delivery.
            return retryBrokerOnly(event, payloadJson, retryCount, "WORKER_DB_ERROR");
        }
        if (claim.state() == TaskDispatchConsumptionService.ClaimState.DUPLICATE) {
            return Action.ACK;
        }
        if (claim.state() == TaskDispatchConsumptionService.ClaimState.IN_PROGRESS) {
            // A processing row is not proof that work completed. If the Worker died after
            // inserting it, ACK would swallow the dispatch until task timeout. Publish a
            // delayed copy (same business messageId) and ACK the old delivery only after it
            // is confirmed; after claim expiry the retry can reclaim and execute.
            try {
                transport.publishRetryAfter(event, payloadJson, retryCount, 120);
                return Action.ACK;
            } catch (RuntimeException exception) {
                log.warn("处理中消息延迟重投失败，保留原消息: messageId={}, reason={}",
                        event.messageId(), exception.getMessage());
                return Action.NO_ACK;
            }
        }
        try {
            return process(event, payloadJson, retryCount, claim.claimToken());
        } catch (RuntimeException exception) {
            log.warn("Worker 消息处理异常: messageId={}, reason={}", event.messageId(),
                    exception.getMessage());
            if (isDatabaseError(exception)) {
                return retryBrokerOnly(event, payloadJson, retryCount, "WORKER_DB_ERROR");
            }
            return retry(event, payloadJson, retryCount, claim.claimToken(),
                    "WORKER_PROCESS_ERROR");
        }
    }

    private Action process(DispatchEvent event, String payloadJson, int retryCount,
                           String claimToken) {
        PreDefenseTask task = taskMapper.selectById(event.taskId());
        if (task == null || !"running".equals(task.getStatus())
                || !event.runId().equals(task.getCurrentRunId())
                || !event.attempt().equals(task.getCurrentAttempt())) {
            return terminal(event, claimToken, DispatchConsumption.OUTCOME_STALE,
                    "DISPATCH_STALE_IDENTITY");
        }
        if (processRegistry.isLocallyAccepted(event.taskId(), event.runId(), event.attempt())) {
            // Narrow race: the executor started between the pre-claim check and the claim.
            // Never terminate the shared ledger here. Release this claim and let a delayed
            // redelivery observe the accepted marker; the original execution keeps ownership.
            return deferLocalAccepted(event, payloadJson, retryCount, claimToken);
        }
        TaskDispatchIntent intent = intentService.findByRun(event.taskId(), event.runId(),
                event.attempt());
        if (intent == null) {
            return terminal(event, claimToken, DispatchConsumption.OUTCOME_STALE,
                    "DISPATCH_INTENT_MISSING");
        }
        if (TaskDispatchIntent.SUBMITTED.equals(intent.getStatus())
                || TaskDispatchIntent.ABANDONED.equals(intent.getStatus())) {
            // No local executor owns this attempt and the dispatch is already past PENDING.
            // A redelivery cannot prove the Python result, so converge the task explicitly
            // instead of leaving it running forever behind a duplicate ledger row.
            dispatchCoordinator.failAndAbandon(event.taskId(), event.runId(), event.attempt(),
                    "DISPATCH_UNCERTAIN_RECOVERY",
                    "消费重投时无本地执行归属，无法确认执行结果，按失败收敛并请用户重试");
            return terminalFromTask(event, claimToken);
        }
        if (TaskDispatchIntent.SUBMITTING.equals(intent.getStatus())) {
            if (processRegistry.isLocallyAccepted(event.taskId(), event.runId(), event.attempt())) {
                return deferLocalAccepted(event, payloadJson, retryCount, claimToken);
            }
            return terminal(event, claimToken, DispatchConsumption.OUTCOME_UNCERTAIN,
                    "DISPATCH_UNCERTAIN_RECOVERY");
        }
        if (!TaskDispatchIntent.PENDING.equals(intent.getStatus())) {
            return terminal(event, claimToken, DispatchConsumption.OUTCOME_STALE,
                    "DISPATCH_INTENT_STATUS_" + intent.getStatus());
        }
        TaskDispatchCoordinator.QueueDispatchResult result = dispatchCoordinator.consume(intent, task);
        switch (result) {
            case TERMINAL:
                return terminalFromTask(event, claimToken);
            case BUSY:
                return retry(event, payloadJson, retryCount, claimToken, "GPU_BUSY");
            case STALE:
                return terminal(event, claimToken, DispatchConsumption.OUTCOME_STALE,
                        "DISPATCH_STALE");
            case UNCERTAIN:
            default:
                // Do not redeliver: a redelivery could start a second Python process.
                return terminal(event, claimToken, DispatchConsumption.OUTCOME_UNCERTAIN,
                        "DISPATCH_UNCERTAIN");
        }
    }

    private Action terminalFromTask(DispatchEvent event, String claimToken) {
        PreDefenseTask task = taskMapper.selectById(event.taskId());
        if (task == null) {
            return terminal(event, claimToken, DispatchConsumption.OUTCOME_UNCERTAIN,
                    "DISPATCH_TASK_MISSING");
        }
        String outcome = switch (task.getStatus()) {
            case "success" -> DispatchConsumption.OUTCOME_SUCCESS;
            case "failed" -> DispatchConsumption.OUTCOME_FAILED;
            case "canceled" -> DispatchConsumption.OUTCOME_CANCELED;
            default -> DispatchConsumption.OUTCOME_UNCERTAIN;
        };
        return terminal(event, claimToken, outcome, "TASK_" + task.getStatus().toUpperCase());
    }

    private Action terminal(DispatchEvent event, String claimToken, String outcome, String code) {
        boolean recorded = consumptionService.markTerminal(event.messageId(), claimToken,
                outcome, code, LocalDateTime.now());
        if (!recorded) {
            return Action.NO_ACK;
        }
        return Action.ACK;
    }

    private Action deferLocalAccepted(DispatchEvent event, String payloadJson, int retryCount,
                                      String claimToken) {
        boolean released;
        try {
            released = consumptionService.releaseForRetry(event.messageId(), claimToken,
                    "DISPATCH_ALREADY_RUNNING_LOCAL", LocalDateTime.now());
        } catch (DataAccessException exception) {
            return retryBrokerOnly(event, payloadJson, retryCount, "WORKER_DB_ERROR");
        }
        if (!released) {
            return Action.ACK;
        }
        try {
            transport.publishRetryAfter(event, payloadJson, retryCount, 120);
            return Action.ACK;
        } catch (RuntimeException exception) {
            log.warn("本地执行中消息延迟重投失败，保留原消息: messageId={}, reason={}",
                    event.messageId(), exception.getMessage());
            return Action.NO_ACK;
        }
    }

    private Action retry(DispatchEvent event, String payloadJson, int retryCount,
                         String claimToken, String code) {
        if (retryCount >= maxRetries) {
            try {
                // Converge the task and its dispatch intent first. The consumption ledger
                // stays non-terminal until both writes succeed, so a DB failure can retry
                // instead of producing "message in DLQ, task still running".
                dispatchCoordinator.failAndAbandon(event.taskId(), event.runId(), event.attempt(),
                        "DISPATCH_RETRY_EXHAUSTED_" + code,
                        "Worker 消费重试耗尽: " + code);
                outboxService.markDeadByMessageId(event.messageId(), code, LocalDateTime.now());
                boolean terminal = consumptionService.markTerminal(event.messageId(), claimToken,
                        DispatchConsumption.OUTCOME_FAILED,
                        "DISPATCH_RETRY_EXHAUSTED_" + code, LocalDateTime.now());
                if (!terminal) {
                    return Action.NO_ACK;
                }
                try {
                    transport.publishDead(event, payloadJson, code);
                } catch (RuntimeException exception) {
                    // The durable convergence is already complete; the DLQ is an index,
                    // not the source of truth for task failure.
                    log.warn("死信发布失败但任务已收敛: messageId={}, reason={}",
                            event.messageId(), exception.getMessage());
                }
                return Action.ACK;
            } catch (DataAccessException exception) {
                log.warn("重试耗尽收敛遇到数据库故障，延迟重投: messageId={}, reason={}",
                        event.messageId(), exception.getMessage());
                return retryBrokerOnly(event, payloadJson, retryCount, "WORKER_DB_ERROR");
            }
        }
        // Release the ledger before publishing: a failed publish leaves the old delivery
        // unacked and the next claim sees a retryable row, not a held processing row.
        boolean released;
        try {
            released = consumptionService.releaseForRetry(event.messageId(), claimToken,
                    code, LocalDateTime.now());
        } catch (DataAccessException exception) {
            return retryBrokerOnly(event, payloadJson, retryCount, "WORKER_DB_ERROR");
        }
        if (!released) {
            // The claim token no longer owns the ledger (another delivery already took it).
            // ACK the stale delivery instead of hot-looping it.
            return Action.ACK;
        }
        try {
            transport.publishRetry(event, payloadJson, retryCount + 1);
            return Action.ACK;
        } catch (RuntimeException exception) {
            log.warn("重试消息发布失败，保留未确认消息: messageId={}, reason={}",
                    event.messageId(), exception.getMessage());
            return Action.NO_ACK;
        }
    }

    private Action retryBrokerOnly(DispatchEvent event, String payloadJson, int retryCount,
                                   String code) {
        try {
            transport.publishRetryAfter(event, payloadJson, retryCount, 120);
            return Action.ACK;
        } catch (RuntimeException exception) {
            log.warn("数据库故障延迟重投失败，保留原消息: messageId={}, reason={}",
                    event.messageId(), exception.getMessage());
            return Action.NO_ACK;
        }
    }

    private boolean isDatabaseError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DataAccessException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
