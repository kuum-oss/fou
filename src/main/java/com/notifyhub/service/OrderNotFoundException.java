package com.notifyhub.service;

/**
 * Thrown when an order cannot be found by its ID.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
