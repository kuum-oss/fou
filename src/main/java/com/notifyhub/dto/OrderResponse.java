package com.notifyhub.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST response DTO for an order with its notifications.
 */
public record OrderResponse(
        UUID id,
        String userId,
        BigDecimal amount,
        String status,
        LocalDateTime createdAt,
        List<NotificationResponse> notifications
) {
}
