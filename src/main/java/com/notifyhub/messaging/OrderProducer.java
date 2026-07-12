package com.notifyhub.messaging;

import com.notifyhub.dto.OrderEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes order events to RabbitMQ.
 * The REST endpoint calls this producer which converts an order request to a queue event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange.orders}")
    private String ordersExchange;

    @Value("${rabbitmq.routing-key.orders}")
    private String ordersRoutingKey;

    /**
     * Publishes an order event to the durable orders queue via direct exchange.
     *
     * @param event the order event DTO to publish
     */
    public void publishOrderEvent(OrderEventDto event) {
        log.info("[OrderProducer] Publishing order event to exchange='{}', routingKey='{}', orderId='{}'",
                ordersExchange, ordersRoutingKey, event.orderId());

        rabbitTemplate.convertAndSend(ordersExchange, ordersRoutingKey, event);

        log.debug("[OrderProducer] Successfully published order event: {}", event);
    }
}
