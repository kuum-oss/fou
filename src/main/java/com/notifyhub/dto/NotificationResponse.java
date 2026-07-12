package com.notifyhub.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REST response DTO for a single notification.
 */
public record NotificationResponse(
        UUID id,
        String type,
        String status,
        LocalDateTime sentAt
) {
}
