package com.notifyhub.service;

import com.notifyhub.domain.Notification;
import com.notifyhub.domain.NotificationStatus;
import com.notifyhub.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduled task that periodically retries S3 uploads for notifications in PENDING_UPLOAD status.
 * Runs every 5 minutes. If S3 becomes available again, notifications are uploaded and marked SENT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3RetryScheduler {

    private final NotificationRepository notificationRepository;
    private final S3ReportService s3ReportService;

    /**
     * Finds all PENDING_UPLOAD notifications and retries the S3 upload.
     * Each failed notification is processed independently — one failure won't stop the others.
     */
    @Scheduled(fixedDelayString = "${scheduler.s3-retry.fixed-delay:300000}") // 5 minutes default
    @Transactional
    public void retryPendingUploads() {
        List<Notification> pendingNotifications =
                notificationRepository.findAllByStatusWithOrder(NotificationStatus.PENDING_UPLOAD);

        if (pendingNotifications.isEmpty()) {
            log.debug("[S3RetryScheduler] No PENDING_UPLOAD notifications found, skipping.");
            return;
        }

        log.info("[S3RetryScheduler] Found {} PENDING_UPLOAD notifications, attempting retry...",
                pendingNotifications.size());

        int successCount = 0;
        int failCount = 0;

        for (Notification notification : pendingNotifications) {
            try {
                s3ReportService.uploadReport(notification.getOrder());
                notification.setStatus(NotificationStatus.SENT);
                notificationRepository.save(notification);
                successCount++;
                log.info("[S3RetryScheduler] Successfully uploaded report for orderId='{}'",
                        notification.getOrder().getId());
            } catch (Exception e) {
                failCount++;
                log.warn("[S3RetryScheduler] Retry failed for orderId='{}': {}",
                        notification.getOrder().getId(), e.getMessage());
            }
        }

        log.info("[S3RetryScheduler] Retry completed: success={}, failed={}", successCount, failCount);
    }
}
