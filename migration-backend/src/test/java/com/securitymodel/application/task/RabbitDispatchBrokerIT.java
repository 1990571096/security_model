package com.securitymodel.application.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RabbitDispatchBrokerIT {

    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin admin;
    private String exchange;
    private String queue;
    private String routingKey;

    @BeforeEach
    void setUp() {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("RABBIT_IT", "false")),
                "RABBIT_IT=true 时才运行真实 RabbitMQ 验收");
        String host = System.getenv().getOrDefault("RABBIT_IT_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("RABBIT_IT_PORT", "5672"));
        String user = System.getenv().getOrDefault("RABBIT_IT_USER", "guest");
        String password = System.getenv().getOrDefault("RABBIT_IT_PASSWORD", "guest");
        String vhost = System.getenv().getOrDefault("RABBIT_IT_VHOST", "/");
        connectionFactory = new CachingConnectionFactory(host, port);
        connectionFactory.setUsername(user);
        connectionFactory.setPassword(password);
        connectionFactory.setVirtualHost(vhost);
        admin = new RabbitAdmin(connectionFactory);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        exchange = "r4.it.exchange." + suffix;
        queue = "r4.it.queue." + suffix;
        routingKey = "r4.it.route." + suffix;
        admin.declareExchange(new DirectExchange(exchange, false, false));
        admin.declareQueue(new Queue(queue, false));
        admin.declareBinding(BindingBuilder.bind(new Queue(queue)).to(new DirectExchange(exchange))
                .with(routingKey));
    }

    @AfterEach
    void tearDown() {
        if (admin != null) {
            admin.deleteQueue(queue);
            admin.deleteExchange(exchange);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void confirmedPublishReachesRealBrokerQueue() {
        RabbitDispatchTransport transport = transport();
        DispatchEvent event = event();
        transport.publish(event, "{\"probe\":\"r4\"}");

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        Message received = template.receive(queue, 5000);
        assertNotNull(received, "真实 Broker 未收到已确认消息");
        assertEquals("{\"probe\":\"r4\"}", new String(received.getBody()));
        assertEquals(event.messageId(), received.getMessageProperties().getMessageId());
    }

    @Test
    void unroutableMandatoryPublishFailsAgainstRealBroker() {
        RabbitDispatchTransport transport = transport();
        admin.deleteQueue(queue);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> transport.publish(event(), "{\"probe\":\"unroutable\"}"));
        assertTrue(error.getMessage().startsWith("RABBIT_UNROUTABLE"));
    }

    private RabbitDispatchTransport transport() {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        return new RabbitDispatchTransport(template, exchange, routingKey, exchange + ".dlx",
                routingKey + ".dead", "r4.it.retry.", 5000L, 500L);
    }

    private DispatchEvent event() {
        return new DispatchEvent(DispatchEvent.SCHEMA_VERSION, UUID.randomUUID().toString(),
                DispatchEvent.EVENT_TASK_DISPATCH, 900L, "run-broker", 1, null, 900L,
                "hash", LocalDateTime.now());
    }
}
