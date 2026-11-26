package com.securitymodel.application.task;

import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The consumer starts only after startup recovery has classified durable intents and
 * acquired the Worker ownership lock. A lost ownership connection stops consumption and
 * closes the launch gate; it never lets a second Worker take over a healthy execution.
 * After the database returns, the same Worker must reacquire ownership, rerun recovery and
 * only then reopen consumption; a database reconnect alone must never start the consumer.
 * If a locally accepted execution is still alive, intake stays closed until its process
 * registry entry clears after the process-tree and GPU-slot decision, so a redelivery
 * cannot open a second execution path. A recovery result that is not explicitly safe also
 * keeps both the scheduling gate and the consumer closed.
 */
@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class WorkerConsumerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerConsumerLifecycle.class);

    private final DispatchRuntimePolicy runtimePolicy;
    private final WorkerOwnershipLease ownershipLease;
    private final SchedulingReadinessGate readinessGate;
    private final DispatchRecoveryCoordinator recoveryCoordinator;
    private final AttemptProcessRegistry processRegistry;
    private final ObjectProvider<SimpleMessageListenerContainer> containerProvider;
    private final AtomicBoolean recovering = new AtomicBoolean(false);
    private final AtomicBoolean consumerStarted = new AtomicBoolean(false);

    public WorkerConsumerLifecycle(
            DispatchRuntimePolicy runtimePolicy,
            WorkerOwnershipLease ownershipLease,
            SchedulingReadinessGate readinessGate,
            DispatchRecoveryCoordinator recoveryCoordinator,
            AttemptProcessRegistry processRegistry,
            ObjectProvider<SimpleMessageListenerContainer> containerProvider) {
        this.runtimePolicy = runtimePolicy;
        this.ownershipLease = ownershipLease;
        this.readinessGate = readinessGate;
        this.recoveryCoordinator = recoveryCoordinator;
        this.processRegistry = processRegistry;
        this.containerProvider = containerProvider;
    }

    public boolean isConsumerStarted() {
        return consumerStarted.get();
    }

    public boolean isRecovering() {
        return recovering.get();
    }

    public boolean startAfterRecovery(DispatchRecoveryCoordinator.RecoveryResult result) {
        if (!runtimePolicy.runsConsumer()) {
            return false;
        }
        if (result == null || !result.safeToConsume()) {
            readinessGate.markNotReady();
            log.error("恢复结果未确认安全，拒绝启动消费: errors={}, localAccepted={}, quarantined={}",
                    result == null ? -1 : result.errors(),
                    result == null ? -1 : result.localAccepted(),
                    result == null ? java.util.List.of("NO_RECOVERY_RESULT") : result.quarantinedSlots());
            return false;
        }
        if (!ownershipLease.isHeld()) {
            readinessGate.markNotReady();
            log.error("Worker 归属租约未持有，拒绝启动消费");
            return false;
        }
        int localAccepted = processRegistry.localAcceptedCount();
        if (localAccepted > 0) {
            // The old attempt is still owned by this JVM. Intake waits until the executor
            // has cleared its accepted marker after the process and lease decision.
            readinessGate.markNotReady();
            log.warn("存在本地已接受执行，消费继续关闭: count={}", localAccepted);
            return false;
        }
        SimpleMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container == null) {
            log.error("Worker 消费容器未装配，拒绝启动");
            readinessGate.markNotReady();
            return false;
        }
        container.start();
        consumerStarted.set(true);
        log.info("Worker 消费容器已在恢复完成后启动");
        return true;
    }

    @Scheduled(fixedDelayString = "${security-model.dispatch.worker.ownership-heartbeat-ms:5000}",
            initialDelayString = "${security-model.dispatch.worker.ownership-heartbeat-ms:5000}")
    public void verifyOwnership() {
        if (!runtimePolicy.runsConsumer()) {
            return;
        }
        if (ownershipLease.isHeld()) {
            if (!consumerStarted.get()) {
                int pendingAccepted = processRegistry.localAcceptedCount();
                if (pendingAccepted > 0) {
                    readinessGate.markNotReady();
                    log.warn("存在本地已接受执行，消费继续关闭: count={}", pendingAccepted);
                    return;
                }
                // The old attempt has cleared its accepted marker. Re-run recovery once more
                // so a result write that failed during the outage is converged against the
                // durable intent before any redelivery can be consumed.
                DispatchRecoveryCoordinator.RecoveryResult result;
                try {
                    result = recoveryCoordinator.recoverAtStartup();
                } catch (RuntimeException exception) {
                    readinessGate.markNotReady();
                    log.error("Worker 归属已持有但恢复扫描失败，消费保持关闭: {}",
                            exception.getMessage(), exception);
                    return;
                }
                if (!result.safeToConsume()) {
                    readinessGate.markNotReady();
                    log.warn("恢复扫描未确认安全，消费保持关闭: errors={}, localAccepted={}, quarantined={}",
                            result.errors(), result.localAccepted(), result.quarantinedSlots());
                    return;
                }
                startAfterRecovery(result);
            }
            return;
        }
        stopConsumerAndCloseGate();
        if (!recovering.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!ownershipLease.acquire()) {
                log.warn("Worker 归属连接仍不可用，保持消费关闭并等待数据库恢复");
                return;
            }
            log.info("Worker 归属连接已恢复，重新执行恢复扫描后再开放消费");
            DispatchRecoveryCoordinator.RecoveryResult result = recoveryCoordinator.recoverAtStartup();
            if (!result.safeToConsume()) {
                readinessGate.markNotReady();
                log.warn("Worker 归属恢复完成但未确认安全，消费保持关闭: errors={}, localAccepted={}, quarantined={}",
                        result.errors(), result.localAccepted(), result.quarantinedSlots());
                return;
            }
            if (startAfterRecovery(result)) {
                log.info("Worker 归属恢复完成，消费已重新开放");
            } else {
                log.warn("Worker 归属恢复完成，但消费保持关闭，等待本地已接受执行结束");
            }
        } catch (RuntimeException exception) {
            readinessGate.markNotReady();
            stopConsumer();
            log.error("Worker 归属恢复失败，消费保持关闭: {}", exception.getMessage(), exception);
        } finally {
            recovering.set(false);
        }
    }

    private void stopConsumerAndCloseGate() {
        readinessGate.markNotReady();
        stopConsumer();
        consumerStarted.set(false);
        log.error("Worker 归属租约已丢失，停止消费并关闭调度门");
    }

    private void stopConsumer() {
        SimpleMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }
}
