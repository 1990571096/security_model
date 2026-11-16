package com.securitymodel.application.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The consumer starts only after startup recovery has classified durable intents and
 * acquired the Worker ownership lock. A lost ownership connection stops consumption and
 * closes the launch gate; it never lets a second Worker take over a healthy execution.
 */
@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class WorkerConsumerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerConsumerLifecycle.class);

    private final DispatchRuntimePolicy runtimePolicy;
    private final WorkerOwnershipLease ownershipLease;
    private final SchedulingReadinessGate readinessGate;
    private final ObjectProvider<SimpleMessageListenerContainer> containerProvider;

    public WorkerConsumerLifecycle(
            DispatchRuntimePolicy runtimePolicy,
            WorkerOwnershipLease ownershipLease,
            SchedulingReadinessGate readinessGate,
            ObjectProvider<SimpleMessageListenerContainer> containerProvider) {
        this.runtimePolicy = runtimePolicy;
        this.ownershipLease = ownershipLease;
        this.readinessGate = readinessGate;
        this.containerProvider = containerProvider;
    }

    public void startAfterRecovery() {
        if (!runtimePolicy.runsConsumer()) {
            return;
        }
        if (!ownershipLease.isHeld()) {
            readinessGate.markNotReady();
            log.error("Worker 归属租约未持有，拒绝启动消费");
            return;
        }
        SimpleMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container == null) {
            log.error("Worker 消费容器未装配，拒绝启动");
            readinessGate.markNotReady();
            return;
        }
        container.start();
        log.info("Worker 消费容器已在恢复完成后启动");
    }

    @Scheduled(fixedDelayString = "${security-model.dispatch.worker.ownership-heartbeat-ms:5000}",
            initialDelayString = "${security-model.dispatch.worker.ownership-heartbeat-ms:5000}")
    public void verifyOwnership() {
        if (!runtimePolicy.runsConsumer()) {
            return;
        }
        if (ownershipLease.isHeld()) {
            return;
        }
        readinessGate.markNotReady();
        SimpleMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container != null && container.isRunning()) {
            container.stop();
        }
        log.error("Worker 归属租约已丢失，停止消费并关闭调度门");
    }
}
