package com.notifyhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifyhub.domain.NotificationStatus;
import com.notifyhub.domain.Order;
import com.notifyhub.domain.OrderStatus;
import com.notifyhub.dto.CreateOrderRequest;
import com.notifyhub.dto.OrderEventDto;
import com.notifyhub.messaging.OrderProducer;
import com.notifyhub.repository.NotificationRepository;
import com.notifyhub.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration test for the full order flow: REST → RabbitMQ → Consumer → Database.
 * Uses Testcontainers to spin up real PostgreSQL and RabbitMQ instances.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class OrderFlowIntegrationTest {

    // ─── Testcontainers ───────────────────────────────────────────────────────────

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("notifyhub_test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    static final RabbitMQContainer rabbitMQ =
            new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // RabbitMQ
        registry.add("spring.rabbitmq.host", rabbitMQ::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitMQ::getAdminPassword);
    }

    // ─── Injected beans ───────────────────────────────────────────────────────────

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OrderProducer orderProducer;

    @Autowired
    private com.notifyhub.security.JwtUtil jwtUtil;

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
        orderRepository.deleteAll();
    }

    // ─── Tests ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /orders → publishes event → consumer persists Order and Notification in DB")
    void whenOrderCreated_thenOrderAndNotificationSavedInDb() throws Exception {
        // WHEN — send HTTP request to create order
        CreateOrderRequest request = new CreateOrderRequest("user-123", new BigDecimal("99.99"));

        String responseBody = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(responseBody).get("orderId").asText();

        // THEN — wait for async consumer to process and save to DB
        await()
                .atMost(15, SECONDS)
                .pollInterval(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(orderRepository.findById(UUID.fromString(orderId))).isPresent();
                });

        Order savedOrder = orderRepository.findByIdWithNotifications(UUID.fromString(orderId)).orElseThrow();
        assertThat(savedOrder.getUserId()).isEqualTo("user-123");
        assertThat(savedOrder.getAmount()).isEqualByComparingTo("99.99");
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);

        // Notification should be created (PENDING_UPLOAD because S3 endpoint is unreachable)
        assertThat(savedOrder.getNotifications()).hasSize(1);
        assertThat(savedOrder.getNotifications().get(0).getStatus())
                .isIn(NotificationStatus.SENT, NotificationStatus.PENDING_UPLOAD);
    }

    @Test
    @DisplayName("RabbitMQ: durable queue survives — message is consumed and saved to PostgreSQL")
    void whenMessagePublishedDirectly_thenConsumedAndPersistedToPostgres() {
        // GIVEN — publish an event directly to RabbitMQ (bypassing REST)
        UUID orderId = UUID.randomUUID();
        OrderEventDto event = new OrderEventDto(orderId, "user-456", new BigDecimal("250.00"), "PENDING");

        // We inject the producer directly
        // (Spring context is fully initialized with real RabbitMQ from Testcontainers)
        // The event will be consumed by OrderConsumer automatically
        orderProducer.publishOrderEvent(event);

        // THEN — wait for the consumer to process and persist
        await()
                .atMost(20, SECONDS)
                .pollInterval(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(orderRepository.findById(orderId)).isPresent();
                    var order = orderRepository.findByIdWithNotifications(orderId).orElseThrow();
                    assertThat(order.getNotifications()).hasSize(1);
                });

        Order savedOrder = orderRepository.findByIdWithNotifications(orderId).orElseThrow();
        assertThat(savedOrder.getUserId()).isEqualTo("user-456");
        assertThat(savedOrder.getAmount()).isEqualByComparingTo("250.00");

        // Verify notification exists in DB
        var notifications = notificationRepository.findByOrderId(orderId);
        assertThat(notifications).isNotEmpty();
    }
}
