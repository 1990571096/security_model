package com.securitymodel.application.task;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DispatchMessageListenerTest {

    private final DispatchMessageHandler handler = mock(DispatchMessageHandler.class);
    private final DispatchMessageListener listener = new DispatchMessageListener(handler);

    @Test
    void negativeRetryHeaderIsRejectedToDeadLetterQueue() throws Exception {
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(9L);
        properties.setHeader("x-retry-count", -1);
        Message message = new Message("{}".getBytes(), properties);

        listener.onMessage(message, channel);

        verify(channel).basicNack(eq(9L), eq(false), eq(false));
        verify(handler, never()).handle(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void nonNumericRetryHeaderIsRejectedToDeadLetterQueue() throws Exception {
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(10L);
        properties.setHeader("x-retry-count", "not-a-number");
        Message message = new Message("{}".getBytes(), properties);

        listener.onMessage(message, channel);

        verify(channel).basicNack(eq(10L), eq(false), eq(false));
        verify(handler, never()).handle(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void deliveredMessageIsAckedOnlyWhenHandlerReturnsAck() throws Exception {
        Channel channel = mock(Channel.class);
        whenHandlerReturns(DispatchMessageHandler.Action.ACK);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(11L);
        Message message = new Message("{}".getBytes(), properties);

        listener.onMessage(message, channel);

        verify(channel).basicAck(eq(11L), anyBoolean());
    }

    private void whenHandlerReturns(DispatchMessageHandler.Action action) {
        org.mockito.Mockito.when(handler.handle(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(action);
    }
}
