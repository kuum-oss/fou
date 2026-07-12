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
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    /**
     * Processes an inbound order event from RabbitMQ.
     * Uses exactly-once semantics via idempotency check.
     * Commits to DB *before* attempting S3 upload to prevent data loss.
     *
     * @param event the incoming order event
     */
    public void processOrderEvent(OrderEventDto event) {
        log.info("[OrderProcessingService] Processing order event: orderId='{}'", event.orderId());

        // Idempotency check: exactly-once processing
        if (orderRepository.existsById(event.orderId())) {
            log.warn("[OrderProcessingService] Duplicate event ignored: {}", event.orderId());
            return;
        }

        // 1. Persist the Order and Notification (as PENDING_UPLOAD) in a single transaction
        Notification notification = transactionTemplate.execute(status -> {
            Order order = Order.builder()
                    .id(event.orderId())
                    .userId(event.userId())
                    .amount(event.amount())
                    .status(OrderStatus.valueOf(event.status()))
                    .build();

            order = orderRepository.save(order);
            log.debug("[OrderProcessingService] Saved order: id='{}'", order.getId());

            Notification notif = Notification.builder()
                    .order(order)
                    .type(NotificationType.EMAIL)
                    .sentAt(LocalDateTime.now())
                    .status(NotificationStatus.PENDING_UPLOAD)
                    .build();

            return notificationRepository.save(notif);
        });

        if (notification == null) {
            log.error("[OrderProcessingService] Failed to persist order/notification");
            return;
        }

        // 2. Upload S3 report outside the transaction
        uploadReportSafely(notification.getOrder(), notification);
    }

    /**
     * Attempts to upload the order report to S3.
     * On success, marks the notification as SENT.
     * On failure, it gracefully degrades. The notification remains PENDING_UPLOAD
     * and the scheduler will retry later.
     */
    private void uploadReportSafely(Order order, Notification notification) {
        try {
            s3ReportService.uploadReport(order);
            log.info("[OrderProcessingService] S3 report uploaded successfully for orderId='{}'", order.getId());
            
            // Mark as SENT in a new transaction
            transactionTemplate.executeWithoutResult(status -> {
                notification.setStatus(NotificationStatus.SENT);
                notificationRepository.save(notification);
            });
        } catch (Exception e) {
            log.warn("[OrderProcessingService] S3 upload failed for orderId='{}', keeping status as PENDING_UPLOAD. Error: {}",
                    order.getId(), e.getMessage());
            // No need to update the DB, it's already PENDING_UPLOAD from step 1
        }
    }
}
