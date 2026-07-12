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

    @Autowired
    private com.notifyhub.service.OrderProcessingService orderProcessingService;

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
        // GIVEN — empty and delete the bucket so that S3 upload fails
        try {
            var objects = s3Client.listObjectsV2(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder().bucket(bucketName).build());
            for (var obj : objects.contents()) {
                s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(bucketName).key(obj.key()).build());
            }
            s3Client.deleteBucket(software.amazon.awssdk.services.s3.model.DeleteBucketRequest.builder().bucket(bucketName).build());
        } catch (Exception ignored) {}

        java.util.UUID orderId = java.util.UUID.randomUUID();
        com.notifyhub.dto.OrderEventDto event = new com.notifyhub.dto.OrderEventDto(orderId, "user-fallback", new BigDecimal("75.00"), "PENDING");

        // WHEN — process the event
        orderProcessingService.processOrderEvent(event);

        // THEN — order is saved, and notification is in PENDING_UPLOAD state
        Order savedOrder = orderRepository.findByIdWithNotifications(orderId).orElseThrow();
        assertThat(savedOrder.getNotifications()).hasSize(1);
        assertThat(savedOrder.getNotifications().get(0).getStatus()).isEqualTo(NotificationStatus.PENDING_UPLOAD);

        // Verify persisted correctly via repository custom method
        List<Notification> pending = notificationRepository
                .findAllByStatusWithOrder(NotificationStatus.PENDING_UPLOAD);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getOrder().getId()).isEqualTo(orderId);
    }
}
