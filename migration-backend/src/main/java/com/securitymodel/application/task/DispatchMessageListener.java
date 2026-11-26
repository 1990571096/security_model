package com.securitymodel.application.task;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class DispatchMessageListener implements ChannelAwareMessageListener {

    private static final Logger log = LoggerFactory.getLogger(DispatchMessageListener.class);

    private final DispatchMessageHandler handler;

    public DispatchMessageListener(DispatchMessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        Object retryHeader = message.getMessageProperties().getHeaders().get("x-retry-count");
        int retryCount;
        try {
            retryCount = retryHeader == null ? 0 : Integer.parseInt(String.valueOf(retryHeader));
        } catch (NumberFormatException exception) {
            log.warn("调度消息重试头非法，拒绝到死信: {}", retryHeader);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        if (retryCount < 0) {
            log.warn("调度消息重试头为负数，拒绝到死信: {}", retryCount);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        DispatchMessageHandler.Action action;
        try {
            action = handler.handle(payload, retryCount);
        } catch (RuntimeException exception) {
            log.error("Worker 消息处理未决异常，保留未确认: {}", exception.getMessage());
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        switch (action) {
            case ACK -> channel.basicAck(deliveryTag, false);
            case REJECT -> channel.basicNack(deliveryTag, false, false);
            case NO_ACK -> channel.basicNack(deliveryTag, false, true);
            default -> channel.basicNack(deliveryTag, false, true);
        }
    }
}
