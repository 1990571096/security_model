package com.securitymodel.application.task;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ReturnListener;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitDispatchTransportTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final Channel channel = mock(Channel.class);
    private final AtomicReference<AMQP.BasicProperties> publishedProperties =
            new AtomicReference<>();
    private final AtomicReference<ReturnListener> returnListener = new AtomicReference<>();

    private RabbitDispatchTransport transport() {
        return new RabbitDispatchTransport(rabbitTemplate, "security-model.dispatch",
                "task.dispatch", "security-model.dispatch.dlx", "task.dispatch.dead",
                "security-model.dispatch.retry.", 1000L, 0L);
    }

    private void wireChannel() throws Exception {
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            try {
                return callback.doInRabbit(channel);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        doAnswer(invocation -> {
            returnListener.set(invocation.getArgument(0));
            return null;
        }).when(channel).addReturnListener(any(ReturnListener.class));
        doAnswer(invocation -> {
            publishedProperties.set(invocation.getArgument(3));
            return null;
        }).when(channel).basicPublish(anyString(), anyString(), anyBoolean(),
                any(AMQP.BasicProperties.class), any(byte[].class));
    }

    @Test
    void nackIsReportedAsPublishFailureNotSuccess() throws Exception {
        wireChannel();
        when(channel.waitForConfirms(anyLong())).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> transport().publish(event(), "{}"));
        assertTrue(error.getMessage().startsWith("RABBIT_PUBLISH_NACK"));
        verify(channel).confirmSelect();
    }

    @Test
    void confirmTimeoutIsReportedAsPublishFailureNotSuccess() throws Exception {
        wireChannel();
        when(channel.waitForConfirms(anyLong()))
                .thenThrow(new java.util.concurrent.TimeoutException("confirm lost"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> transport().publish(event(), "{}"));
        assertTrue(error.getMessage().startsWith("RABBIT_PUBLISH_FAILED"));
        Throwable cause = error.getCause();
        while (cause != null && !(cause instanceof java.util.concurrent.TimeoutException)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof java.util.concurrent.TimeoutException);
    }

    @Test
    void mandatoryReturnIsReportedAsUnroutable() throws Exception {
        wireChannel();
        when(channel.waitForConfirms(anyLong())).thenAnswer(invocation -> {
            AMQP.BasicProperties properties = publishedProperties.get();
            returnListener.get().handleReturn(312, "NO_ROUTE", "security-model.dispatch",
                    "task.dispatch", properties, "{}".getBytes());
            return true;
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> transport().publish(event(), "{}"));
        assertTrue(error.getMessage().startsWith("RABBIT_UNROUTABLE"));
    }

    @Test
    void channelIoFailureKeepsCause() throws Exception {
        wireChannel();
        doThrow(new IOException("connection reset")).when(channel)
                .basicPublish(anyString(), anyString(), anyBoolean(),
                        any(AMQP.BasicProperties.class), any(byte[].class));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> transport().publish(event(), "{}"));
        assertTrue(error.getMessage().startsWith("RABBIT_PUBLISH_FAILED"));
        Throwable cause = error.getCause();
        while (cause != null && !(cause instanceof IOException)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof IOException);
    }

    @Test
    void retryPublishesToDelayQueueWithRetryHeader() throws Exception {
        wireChannel();
        when(channel.waitForConfirms(anyLong())).thenReturn(true);

        transport().publishRetryAfter(event(), "{}", 2, 30);

        verify(channel).basicPublish(eq(""), eq("security-model.dispatch.retry.30s"),
                eq(true), any(AMQP.BasicProperties.class), any(byte[].class));
        assertEquals(2, publishedProperties.get().getHeaders().get("x-retry-count"));
    }

    private DispatchEvent event() {
        return new DispatchEvent(DispatchEvent.SCHEMA_VERSION, "m-1",
                DispatchEvent.EVENT_TASK_DISPATCH, 11L, "run-1", 1, null, 7L, "hash",
                LocalDateTime.now());
    }
}
