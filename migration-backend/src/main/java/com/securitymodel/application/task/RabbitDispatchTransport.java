package com.securitymodel.application.task;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ReturnListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class RabbitDispatchTransport implements DispatchTransport {

    private static final Logger log = LoggerFactory.getLogger(RabbitDispatchTransport.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final String deadLetterExchange;
    private final String deadLetterRoutingKey;
    private final String retryPrefix;
    private final long confirmTimeoutMs;
    /**
     * Spring AMQP may deliver the mandatory return callback on the connection thread just
     * after completing the confirm future. A short, per-publish settlement wait closes that
     * callback-ordering race; it never replaces the broker confirm, and a returned message
     * always wins over an ack.
     */
    private final long returnSettleMs;

    public RabbitDispatchTransport(
            RabbitTemplate rabbitTemplate,
            @Value("${security-model.dispatch.rabbit.exchange:security-model.dispatch}") String exchange,
            @Value("${security-model.dispatch.rabbit.routing-key:task.dispatch}") String routingKey,
            @Value("${security-model.dispatch.rabbit.dead-letter-exchange:security-model.dispatch.dlx}")
            String deadLetterExchange,
            @Value("${security-model.dispatch.rabbit.dead-letter-routing-key:task.dispatch.dead}")
            String deadLetterRoutingKey,
            @Value("${security-model.dispatch.rabbit.retry-queue-prefix:security-model.dispatch.retry.}")
            String retryPrefix,
            @Value("${security-model.dispatch.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${security-model.dispatch.outbox.return-settle-ms:200}") long returnSettleMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.deadLetterExchange = deadLetterExchange;
        this.deadLetterRoutingKey = deadLetterRoutingKey;
        this.retryPrefix = retryPrefix;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.returnSettleMs = returnSettleMs;
    }

    @Override
    public void publish(DispatchEvent event, String payloadJson) {
        send(exchange, routingKey, event, payloadJson, null);
    }

    @Override
    public void publishRetry(DispatchEvent event, String payloadJson, int retryCount) {
        publishRetryAfter(event, payloadJson, retryCount, retryDelaySeconds(retryCount));
    }

    @Override
    public void publishRetryAfter(DispatchEvent event, String payloadJson, int retryCount,
                                  int delaySeconds) {
        String queue = retryPrefix + delayLabel(delaySeconds);
        send("", queue, event, payloadJson, retryCount);
    }

    @Override
    public void publishDead(DispatchEvent event, String payloadJson, String reason) {
        send(deadLetterExchange, deadLetterRoutingKey, event, payloadJson, null);
    }

    private void send(String targetExchange, String targetRoutingKey, DispatchEvent event,
                      String payloadJson, Integer retryCount) {
        // The business message id stays stable for idempotent outbox retries; the
        // correlation id is unique per publish so confirm/return cannot cross-talk.
        String publishId = UUID.randomUUID().toString();
        Map<String, Object> headers = new HashMap<>();
        if (retryCount != null) {
            headers.put("x-retry-count", retryCount);
        }
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .contentEncoding("UTF-8")
                .deliveryMode(2)
                .messageId(event.messageId())
                .correlationId(publishId)
                .headers(headers)
                .build();
        AtomicBoolean returned = new AtomicBoolean(false);
        CountDownLatch returnLatch = new CountDownLatch(1);
        ReturnListener returnListener = (replyCode, replyText, exchangeName, routingKey, properties1, body) -> {
            if (publishId.equals(properties1.getCorrelationId())) {
                log.warn("RabbitMQ 消息被退回: publishId={}, businessMessageId={}, replyCode={}, replyText={}",
                        publishId, event.messageId(), replyCode, replyText);
                returned.set(true);
                returnLatch.countDown();
            }
        };
        try {
            // Register a return listener on the exact channel used for this publish, then
            // confirm on that same channel. RabbitMQ emits basic.return before basic.ack,
            // so a mandatory return is observed deterministically, never via a shared map
            // or a sleep-based settlement guess.
            rabbitTemplate.execute((Channel channel) -> {
                channel.addReturnListener(returnListener);
                try {
                    channel.confirmSelect();
                    channel.basicPublish(targetExchange, targetRoutingKey, true, properties,
                            payloadJson.getBytes(StandardCharsets.UTF_8));
                    if (!channel.waitForConfirms(confirmTimeoutMs)) {
                        throw new IllegalStateException("RABBIT_PUBLISH_NACK: Broker 未确认消息 "
                                + event.messageId());
                    }
                    if (!returned.get() && returnSettleMs > 0) {
                        returnLatch.await(returnSettleMs, TimeUnit.MILLISECONDS);
                    }
                    if (returned.get()) {
                        throw new IllegalStateException("RABBIT_UNROUTABLE: 消息不可路由 "
                                + event.messageId());
                    }
                    log.info("RabbitMQ 发布已确认: publishId={}, businessMessageId={}",
                            publishId, event.messageId());
                    return null;
                } finally {
                    try {
                        channel.removeReturnListener(returnListener);
                    } catch (Exception ignored) {
                        // channel may already be closed; the publish outcome is already known
                    }
                }
            });
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof IllegalStateException state) {
                    throw state;
                }
                cause = cause.getCause();
            }
            throw new IllegalStateException("RABBIT_PUBLISH_FAILED: 发布确认失败 "
                    + event.messageId(), exception);
        }
    }

    private int retryDelaySeconds(int retryCount) {
        if (retryCount <= 1) {
            return 5;
        }
        if (retryCount == 2) {
            return 30;
        }
        return 120;
    }

    private String delayLabel(int delaySeconds) {
        return delaySeconds + "s";
    }

}
