package com.securitymodel.application.task;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rabbit topology for the single-machine R4 design. Only the main queue is consumed; retry
 * queues use TTL + DLX back to the main exchange so a bounded retry never blocks the consumer.
 */
@Configuration
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class RabbitDispatchConfiguration {

    @Bean
    public DirectExchange dispatchExchange(
            @Value("${security-model.dispatch.rabbit.exchange:security-model.dispatch}") String name) {
        return new DirectExchange(name, true, false);
    }

    @Bean
    public DirectExchange dispatchDeadLetterExchange(
            @Value("${security-model.dispatch.rabbit.dead-letter-exchange:security-model.dispatch.dlx}")
            String name) {
        return new DirectExchange(name, true, false);
    }

    @Bean
    public Queue dispatchWorkerQueue(
            @Value("${security-model.dispatch.rabbit.queue:security-model.dispatch.worker}") String name,
            @Value("${security-model.dispatch.rabbit.dead-letter-exchange:security-model.dispatch.dlx}")
            String deadLetterExchange,
            @Value("${security-model.dispatch.rabbit.dead-letter-routing-key:task.dispatch.dead}")
            String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    @Bean
    public Binding dispatchWorkerBinding(@Qualifier("dispatchExchange") DirectExchange dispatchExchange,
                                         @Qualifier("dispatchWorkerQueue") Queue dispatchWorkerQueue,
                                         @Value("${security-model.dispatch.rabbit.routing-key:task.dispatch}")
                                         String routingKey) {
        return BindingBuilder.bind(dispatchWorkerQueue).to(dispatchExchange).with(routingKey);
    }

    @Bean
    public Queue dispatchDeadLetterQueue(
            @Value("${security-model.dispatch.rabbit.dead-letter-queue:security-model.dispatch.dlq}")
            String name) {
        return QueueBuilder.durable(name).build();
    }

    @Bean
    public Binding dispatchDeadLetterBinding(
            @Qualifier("dispatchDeadLetterExchange") DirectExchange dispatchDeadLetterExchange,
            @Qualifier("dispatchDeadLetterQueue") Queue dispatchDeadLetterQueue,
            @Value("${security-model.dispatch.rabbit.dead-letter-routing-key:task.dispatch.dead}")
            String routingKey) {
        return BindingBuilder.bind(dispatchDeadLetterQueue)
                .to(dispatchDeadLetterExchange).with(routingKey);
    }

    @Bean
    public Queue dispatchRetry5sQueue(
            @Value("${security-model.dispatch.rabbit.retry-queue-prefix:security-model.dispatch.retry.}")
            String prefix,
            @Value("${security-model.dispatch.rabbit.exchange:security-model.dispatch}") String exchange,
            @Value("${security-model.dispatch.rabbit.routing-key:task.dispatch}") String routingKey) {
        return retryQueue(prefix + "5s", 5_000, exchange, routingKey);
    }

    @Bean
    public Queue dispatchRetry30sQueue(
            @Value("${security-model.dispatch.rabbit.retry-queue-prefix:security-model.dispatch.retry.}")
            String prefix,
            @Value("${security-model.dispatch.rabbit.exchange:security-model.dispatch}") String exchange,
            @Value("${security-model.dispatch.rabbit.routing-key:task.dispatch}") String routingKey) {
        return retryQueue(prefix + "30s", 30_000, exchange, routingKey);
    }

    @Bean
    public Queue dispatchRetry120sQueue(
            @Value("${security-model.dispatch.rabbit.retry-queue-prefix:security-model.dispatch.retry.}")
            String prefix,
            @Value("${security-model.dispatch.rabbit.exchange:security-model.dispatch}") String exchange,
            @Value("${security-model.dispatch.rabbit.routing-key:task.dispatch}") String routingKey) {
        return retryQueue(prefix + "120s", 120_000, exchange, routingKey);
    }

    @Bean
    @ConditionalOnExpression(
            "'${security-model.dispatch.role:api}' == 'worker' or '${security-model.dispatch.role:api}' == 'all'")
    public SimpleMessageListenerContainer dispatchWorkerContainer(
            ConnectionFactory connectionFactory,
            DispatchMessageListener dispatchMessageListener,
            @Value("${security-model.dispatch.rabbit.queue:security-model.dispatch.worker}") String queue) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queue);
        container.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setMessageListener(dispatchMessageListener);
        container.setMissingQueuesFatal(false);
        // Recovery and ownership acquisition happen first; TaskRecoveryRunner starts this.
        container.setAutoStartup(false);
        return container;
    }

    private Queue retryQueue(String name, int ttlMs, String exchange, String routingKey) {
        return QueueBuilder.durable(name)
                .ttl(ttlMs)
                .deadLetterExchange(exchange)
                .deadLetterRoutingKey(routingKey)
                .build();
    }
}
