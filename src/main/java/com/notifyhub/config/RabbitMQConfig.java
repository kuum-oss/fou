package com.notifyhub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * RabbitMQ configuration.
 * Declares durable queues, exchange, and binding so messages survive broker restarts.
 * Uses JSON message converter for clean serialization of DTOs.
 */
@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.queue.orders}")
    private String ordersQueue;

    @Value("${rabbitmq.queue.orders-dlq}")
    private String ordersDlq;

    @Value("${rabbitmq.exchange.orders}")
    private String ordersExchange;

    @Value("${rabbitmq.routing-key.orders}")
    private String ordersRoutingKey;

    // ─── Queues ──────────────────────────────────────────────────────────────────

    /**
     * Main orders queue — durable so messages survive broker restart.
     * Messages that fail after max retries go to the dead-letter queue.
     */
    @Bean
    public Queue ordersQueue() {
        return QueueBuilder.durable(ordersQueue)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", ordersDlq)
                .build();
    }

    /**
     * Dead-letter queue for failed order messages — durable.
     */
    @Bean
    public Queue ordersDlq() {
        return QueueBuilder.durable(ordersDlq).build();
    }

    // ─── Exchange ─────────────────────────────────────────────────────────────────

    /**
     * Direct exchange — routes messages by exact routing key.
     * Durable so it survives broker restarts.
     */
    @Bean
    public DirectExchange ordersExchange() {
        return ExchangeBuilder.directExchange(ordersExchange)
                .durable(true)
                .build();
    }

    // ─── Binding ─────────────────────────────────────────────────────────────────

    @Bean
    public Binding ordersBinding(Queue ordersQueue, DirectExchange ordersExchange) {
        return BindingBuilder.bind(ordersQueue)
                .to(ordersExchange)
                .with(ordersRoutingKey);
    }

    // ─── Message Converter ────────────────────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);

        // Publisher confirms for reliable publishing
        RetryTemplate retryTemplate = new RetryTemplate();
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(500L);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(10000L);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        template.setRetryTemplate(retryTemplate);

        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false); // send to DLQ instead of requeueing indefinitely
        return factory;
    }
}
