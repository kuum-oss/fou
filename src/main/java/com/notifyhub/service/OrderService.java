package com.notifyhub.service;

import com.notifyhub.domain.Order;
import com.notifyhub.domain.Notification;
import com.notifyhub.domain.NotificationStatus;
import com.notifyhub.dto.CreateOrderRequest;
import com.notifyhub.dto.NotificationResponse;
import com.notifyhub.dto.OrderEventDto;
import com.notifyhub.dto.OrderResponse;
import com.notifyhub.messaging.OrderProducer;
import com.notifyhub.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application service for order-related REST operations.
 * Handles validation, event publishing, and query operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderProducer orderProducer;
    private final S3ReportService s3ReportService;

    /**
     * Accepts a create-order request, assigns a UUID, and publishes the event to RabbitMQ.
     * The actual persistence happens in the consumer.
     *
     * @param request validated order request
     * @return the generated order UUID
     */
    public UUID createOrder(CreateOrderRequest request) {
        UUID orderId = UUID.randomUUID();
        log.info("[OrderService] Creating order: userId='{}', amount='{}', generatedId='{}'",
                request.userId(), request.amount(), orderId);

        OrderEventDto event = new OrderEventDto(orderId, request.userId(), request.amount(), "PENDING");
        orderProducer.publishOrderEvent(event);

        log.debug("[OrderService] Published order event for orderId='{}'", orderId);
        return orderId;
    }

    /**
     * Retrieves all orders for a user with their notifications — N+1 safe via JOIN FETCH.
     *
     * @param userId the user identifier
     * @return list of order responses with nested notifications
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForUser(String userId) {
        log.info("[OrderService] Fetching orders with notifications for userId='{}'", userId);
        List<Order> orders = orderRepository.findAllByUserIdWithNotifications(userId);
        log.debug("[OrderService] Found {} orders for userId='{}'", orders.size(), userId);
        return orders.stream().map(this::toResponse).toList();
    }

    /**
     * Returns a presigned S3 URL for the order's report file.
     *
     * @param orderId the order UUID
     * @return presigned URL valid for 15 minutes
     */
    @Transactional(readOnly = true)
    public String getReportUrl(UUID orderId) {
        log.info("[OrderService] Fetching presigned report URL for orderId='{}'", orderId);
        // Validate order exists
        orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        return s3ReportService.generatePresignedUrl(orderId);
    }

    // ─── Mappers ─────────────────────────────────────────────────────────────────

    private OrderResponse toResponse(Order order) {
        List<NotificationResponse> notifs = order.getNotifications().stream()
                .map(this::toNotificationResponse)
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getAmount(),
                order.getStatus().name(),
                order.getCreatedAt(),
                notifs
        );
    }

    private NotificationResponse toNotificationResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getStatus().name(),
                notification.getSentAt()
        );
    }
}
