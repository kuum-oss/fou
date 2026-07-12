package com.notifyhub.service;

import com.notifyhub.domain.Notification;
import com.notifyhub.domain.NotificationStatus;
import com.notifyhub.domain.NotificationType;
import com.notifyhub.domain.Order;
import com.notifyhub.domain.OrderStatus;
import com.notifyhub.dto.OrderEventDto;
import com.notifyhub.repository.NotificationRepository;
import com.notifyhub.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Core service that handles order event processing.
 * Orchestrates: Order persistence → Notification creation → S3 report upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProcessingService {

    private final OrderRepository orderRepository;
    private final NotificationRepository notificationRepository;
    private final S3ReportService s3ReportService;

    /**
     * Processes an inbound order event from RabbitMQ.
     * <ol>
     *   <li>Persists the Order entity</li>
     *   <li>Creates an EMAIL Notification entity with status SENT</li>
     *   <li>Attempts to upload a report to S3 — on failure, sets PENDING_UPLOAD (no exception propagated)</li>
     * </ol>
     *
     * @param event the incoming order event
     */
    @Transactional
    public void processOrderEvent(OrderEventDto event) {
        log.info("[OrderProcessingService] Processing order event: orderId='{}'", event.orderId());

        // 1. Persist the Order
        Order order = Order.builder()
                .id(event.orderId())
                .userId(event.userId())
                .amount(event.amount())
                .status(OrderStatus.valueOf(event.status()))
                .build();

        order = orderRepository.save(order);
        log.debug("[OrderProcessingService] Saved order: id='{}'", order.getId());

        // 2. Create and persist a Notification
        Notification notification = Notification.builder()
                .order(order)
                .type(NotificationType.EMAIL)
                .sentAt(LocalDateTime.now())
                .status(NotificationStatus.SENT)
                .build();

        notification = notificationRepository.save(notification);
        log.info("[OrderProcessingService] Created notification: id='{}', orderId='{}'",
                notification.getId(), order.getId());

        // 3. Upload S3 report — graceful degradation on failure
        uploadReportSafely(order, notification);
    }

    /**
     * Attempts to upload the order report to S3.
     * If S3 is unavailable, sets the notification status to PENDING_UPLOAD
     * so the scheduler can retry later. Never throws — the main flow must not fail.
     */
    private void uploadReportSafely(Order order, Notification notification) {
        try {
            s3ReportService.uploadReport(order);
            log.info("[OrderProcessingService] S3 report uploaded successfully for orderId='{}'", order.getId());
        } catch (Exception e) {
            log.warn("[OrderProcessingService] S3 upload failed for orderId='{}', setting status to PENDING_UPLOAD. Error: {}",
                    order.getId(), e.getMessage());
            notification.setStatus(NotificationStatus.PENDING_UPLOAD);
            notificationRepository.save(notification);
        }
    }
}
