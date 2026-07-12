package com.notifyhub.controller;

import com.notifyhub.dto.CreateOrderRequest;
import com.notifyhub.dto.OrderResponse;
import com.notifyhub.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for order management.
 * POST  /orders            — publish an order event to RabbitMQ
 * GET   /orders/user/{userId} — get all orders with notifications for a user (N+1 safe)
 * GET   /orders/{id}/report   — get presigned S3 URL for the order report
 */
@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Creates a new order event and publishes it to RabbitMQ.
     *
     * @param request validated order request body
     * @return 202 Accepted with the generated order ID
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        log.info("[OrderController] POST /orders - userId='{}', amount='{}'",
                request.userId(), request.amount());

        UUID orderId = orderService.createOrder(request);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "orderId", orderId.toString(),
                        "message", "Order accepted and queued for processing"
                ));
    }

    /**
     * Returns all orders for the given user, with notifications pre-loaded (N+1 safe).
     *
     * @param userId the user identifier
     * @return list of order responses
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getOrdersForUser(@PathVariable String userId) {
        log.info("[OrderController] GET /orders/user/{} - fetching orders with notifications", userId);
        List<OrderResponse> orders = orderService.getOrdersForUser(userId);
        return ResponseEntity.ok(orders);
    }

    /**
     * Returns a presigned S3 URL for downloading the order report.
     * URL is valid for 15 minutes.
     *
     * @param id the order UUID
     * @return presigned URL
     */
    @GetMapping("/{id}/report")
    public ResponseEntity<Map<String, String>> getReportUrl(@PathVariable UUID id) {
        log.info("[OrderController] GET /orders/{}/report - generating presigned URL", id);
        String url = orderService.getReportUrl(id);
        return ResponseEntity.ok(Map.of("presignedUrl", url));
    }
}
