package com.notifyhub.domain;

/**
 * Notification delivery status.
 * PENDING_UPLOAD — notification saved in DB, but S3 report upload failed; will be retried.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    PENDING_UPLOAD
}
