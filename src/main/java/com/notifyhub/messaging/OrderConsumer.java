package com.notifyhub.messaging;

import com.notifyhub.dto.OrderEventDto;
import com.notifyhub.service.OrderProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes order events from RabbitMQ and persists them to the database.
 * On each message the consumer:
 * 1. Saves the Order entity
 * 2. Creates a Notification entity
 * 3. Attempts to upload the S3 report (with graceful fallback)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConsumer {

    private final OrderProcessingService orderProcessingService;

    /**
     * Listens to the durable orders queue.
     * Spring AMQP handles acknowledgements automatically (AUTO ack mode).
     *
     * @param event the deserialized order event
     */
    @RabbitListener(queues = "${rabbitmq.queue.orders}")
    public void handleOrderEvent(OrderEventDto event) {
        log.info("[OrderConsumer] Received order event: orderId='{}', userId='{}', amount='{}'",
                event.orderId(), event.userId(), event.amount());

        try {
            orderProcessingService.processOrderEvent(event);
            log.info("[OrderConsumer] Successfully processed order event: orderId='{}'", event.orderId());
        } catch (Exception e) {
            log.error("[OrderConsumer] Failed to process order event: orderId='{}', error='{}'",
                    event.orderId(), e.getMessage(), e);
            // Re-throw so Spring AMQP retries according to retry policy
            throw e;
        }
    }
}
