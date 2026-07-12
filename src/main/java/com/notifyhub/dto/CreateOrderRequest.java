package com.notifyhub.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * REST request DTO for creating a new order.
 */
public record CreateOrderRequest(
        @NotBlank(message = "userId must not be blank")
        String userId,

        @NotNull(message = "amount must not be null")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        BigDecimal amount
) {
}
