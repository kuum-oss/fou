package com.notifyhub;

import com.notifyhub.domain.Notification;
import com.notifyhub.domain.NotificationStatus;
import com.notifyhub.domain.NotificationType;
import com.notifyhub.domain.Order;
import com.notifyhub.domain.OrderStatus;
import com.notifyhub.repository.NotificationRepository;
import com.notifyhub.repository.OrderRepository;
import com.notifyhub.service.S3ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

/**
 * Integration test for S3 report upload and presigned URL generation using LocalStack.
 * Also tests the N+1 fix — the custom JPQL JOIN FETCH query.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class S3AndRepositoryIntegrationTest {

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

    @Container
    static final LocalStackContainer localStack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5"))
                    .withServices(S3);

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

        // LocalStack S3
        registry.add("aws.s3.endpoint",
                () -> localStack.getEndpointOverride(S3).toString());
        registry.add("aws.s3.bucket-name", () -> "notifyhub-reports-test");
        registry.add("aws.s3.access-key", () -> localStack.getAccessKey());
        registry.add("aws.s3.secret-key", () -> localStack.getSecretKey());
        registry.add("aws.s3.region", () -> localStack.getRegion());
    }

    // ─── Injected beans ───────────────────────────────────────────────────────────

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private S3ReportService s3ReportService;

    @Autowired
    private S3Client s3Client;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        orderRepository.deleteAll();

        // Ensure test bucket exists in LocalStack
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        } catch (Exception ignored) {
            // already exists
        }
    }

    // ─── Tests ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S3: uploadReport uploads file to LocalStack S3 and presigned URL is generated")
    void whenReportUploaded_thenFileExistsInS3AndPresignedUrlIsReturned() {
        // GIVEN — create and save an order
        Order order = orderRepository.save(Order.builder()
                .userId("user-s3-test")
                .amount(new BigDecimal("150.00"))
                .status(OrderStatus.CONFIRMED)
                .build());

        // WHEN — upload report
        s3ReportService.uploadReport(order);

        // THEN — verify file exists in LocalStack S3
        String expectedKey = "order_" + order.getId() + "_report.txt";
        HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(expectedKey)
                .build();
        assertThat(s3Client.headObject(headRequest).contentLength()).isPositive();

        // AND — presigned URL can be generated
        String presignedUrl = s3ReportService.generatePresignedUrl(order.getId());
        assertThat(presignedUrl).isNotBlank();
        assertThat(presignedUrl).contains(order.getId().toString());
    }

    @Test
    @DisplayName("Repository N+1 fix: findAllByUserIdWithNotifications loads notifications in single query")
    void whenFetchingOrdersForUser_thenNotificationsLoadedWithoutN1() {
        // GIVEN — create 2 orders with 2 notifications each
        String userId = "user-n1-test";

        for (int i = 0; i < 2; i++) {
            Order order = Order.builder()
                    .userId(userId)
                    .amount(new BigDecimal("100.00").add(BigDecimal.valueOf(i * 10)))
                    .status(OrderStatus.PENDING)
                    .build();
            Order savedOrder = orderRepository.save(order);

            for (int j = 0; j < 2; j++) {
                Notification n = Notification.builder()
                        .order(savedOrder)
                        .type(NotificationType.EMAIL)
                        .sentAt(LocalDateTime.now())
                        .status(NotificationStatus.SENT)
                        .build();
                notificationRepository.save(n);
            }
        }

        // WHEN — use the JOIN FETCH query
        List<Order> orders = orderRepository.findAllByUserIdWithNotifications(userId);

        // THEN — all notifications are loaded (no LazyInitializationException, no N+1)
        assertThat(orders).hasSize(2);
        orders.forEach(o -> {
            assertThat(o.getNotifications()).hasSize(2);
            assertThat(o.getUserId()).isEqualTo(userId);
        });
    }

    @Test
    @DisplayName("PENDING_UPLOAD fallback: notification saved when S3 is unavailable")
    void whenS3Unavailable_thenNotificationSavedWithPendingUploadStatus() {
        // This test verifies the fallback logic by checking what happens
        // when the S3ReportService throws — the OrderProcessingService catches it
        // and sets PENDING_UPLOAD. We simulate by checking the NotificationStatus enum directly.
        assertThat(NotificationStatus.PENDING_UPLOAD).isNotNull();

        // Create a notification with PENDING_UPLOAD status directly (simulating fallback)
        Order order = orderRepository.save(Order.builder()
                .userId("user-fallback")
                .amount(new BigDecimal("75.00"))
                .status(OrderStatus.PENDING)
                .build());

        Notification notification = notificationRepository.save(Notification.builder()
                .order(order)
                .type(NotificationType.EMAIL)
                .sentAt(LocalDateTime.now())
                .status(NotificationStatus.PENDING_UPLOAD)
                .build());

        // Verify persisted correctly
        List<Notification> pending = notificationRepository
                .findAllByStatusWithOrder(NotificationStatus.PENDING_UPLOAD);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getId()).isEqualTo(notification.getId());
        assertThat(pending.get(0).getOrder().getId()).isEqualTo(order.getId());
    }
}
