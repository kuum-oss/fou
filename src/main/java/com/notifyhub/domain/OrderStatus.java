package com.notifyhub.domain;

/**
 * Lifecycle statuses for an Order.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
