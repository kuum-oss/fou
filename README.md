# NotifyHub — E-Commerce Notification Microservice

A production-ready notification microservice for e-commerce platforms built with **Java 21**, **Spring Boot 3**, **RabbitMQ**, **PostgreSQL**, and **AWS S3** (LocalStack for local development).

## Architecture Overview

```
POST /orders
     │
     ▼
OrderController ──► OrderProducer ──► RabbitMQ (orders.queue, durable)
                                              │
                                              ▼
                                       OrderConsumer
                                              │
                            ┌─────────────────┼──────────────────┐
                            ▼                 ▼                  ▼
                      OrderRepository  NotificationRepository  S3ReportService
                            │                 │                  │
                            ▼                 ▼                  ▼
                        PostgreSQL        PostgreSQL           LocalStack S3
```

### Key Design Decisions

| Topic | Implementation |
|-------|---------------|
| **Hibernate N+1** | `@Query` with `JOIN FETCH` in `OrderRepository` loads orders + notifications in a single SQL |
| **RabbitMQ** | Durable queue + DLQ, JSON serialization, exponential backoff retry |
| **S3 Fallback** | If S3 upload fails → notification saved with `PENDING_UPLOAD` status, never throws |
| **Retry Scheduler** | `@Scheduled` every 5 min retries all `PENDING_UPLOAD` notifications |
| **Error Handling** | RFC 7807 Problem Details, structured JSON errors |

---

## Prerequisites

- Docker & Docker Compose
- Java 21 (for running tests locally)
- Maven 3.9+

---

## Quick Start

### 1. Start all services with Docker Compose

```bash
docker compose up --build -d
```

This starts:
- **PostgreSQL** on port `5432`
- **RabbitMQ** on port `5672` | Management UI on `http://localhost:15672` (guest/guest)
- **LocalStack S3** on port `4566`
- **NotifyHub** on port `8080`

### 2. Verify everything is running

```bash
docker compose ps
docker compose logs -f notifyhub
```

### 3. Test the API

#### Create an order (publishes to RabbitMQ)
```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-123", "amount": 99.99}'
```

Response:
```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Order accepted and queued for processing"
}
```

#### Get all orders for a user (with notifications — N+1 safe)
```bash
curl http://localhost:8080/orders/user/user-123
```

#### Get presigned S3 URL for an order report
Replace `<ORDER_ID>` with the actual `orderId` returned from the POST request.

```bash
curl http://localhost:8080/orders/<ORDER_ID>/report
```

Response:
```json
{
  "presignedUrl": "http://localhost:4566/notifyhub-reports/order_<ORDER_ID>_report.txt?..."
}
```

---

## Running Tests

```bash
# Run all integration tests (requires Docker for Testcontainers)
./mvnw test
```

Tests use Testcontainers to automatically spin up:
- `PostgreSQL 16` — real database
- `RabbitMQ 3.13` — real message broker  
- `LocalStack 3.5` — real S3 emulator

No mocking of infrastructure!

---

## Project Structure

```
src/
├── main/java/com/notifyhub/
│   ├── NotifyHubApplication.java      # Entry point
│   ├── config/
│   │   ├── RabbitMQConfig.java        # Durable queue, exchange, DLQ, JSON converter
│   │   └── S3Config.java              # S3Client + S3Presigner for LocalStack
│   ├── controller/
│   │   ├── OrderController.java       # REST endpoints
│   │   └── GlobalExceptionHandler.java # RFC 7807 error handling
│   ├── domain/
│   │   ├── Order.java                 # @Entity with @OneToMany (LAZY)
│   │   ├── Notification.java          # @Entity with @ManyToOne
│   │   ├── OrderStatus.java
│   │   ├── NotificationStatus.java    # Includes PENDING_UPLOAD
│   │   └── NotificationType.java
│   ├── dto/
│   │   ├── CreateOrderRequest.java    # Validated REST request
│   │   ├── OrderEventDto.java         # RabbitMQ message DTO
│   │   ├── OrderResponse.java
│   │   └── NotificationResponse.java
│   ├── messaging/
│   │   ├── OrderProducer.java         # Publishes to orders.exchange
│   │   └── OrderConsumer.java         # @RabbitListener on orders.queue
│   ├── repository/
│   │   ├── OrderRepository.java       # JOIN FETCH query (N+1 fix)
│   │   └── NotificationRepository.java
│   └── service/
│       ├── OrderService.java          # REST service layer
│       ├── OrderProcessingService.java # Consumer orchestration
│       ├── S3ReportService.java       # Upload + presigned URL
│       └── S3RetryScheduler.java      # @Scheduled retry for PENDING_UPLOAD
├── main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_orders_table.sql
│       └── V2__create_notifications_table.sql
└── test/java/com/notifyhub/
    ├── OrderFlowIntegrationTest.java  # RabbitMQ + PostgreSQL
    └── S3AndRepositoryIntegrationTest.java # S3 + N+1 + PENDING_UPLOAD
```

---

## N+1 Problem — How It's Solved

**Without the fix** (classic N+1):
```sql
-- 1 query for orders
SELECT * FROM orders WHERE user_id = ?
-- N queries for notifications (one per order!)
SELECT * FROM notifications WHERE order_id = ?  -- for order 1
SELECT * FROM notifications WHERE order_id = ?  -- for order 2
...
```

**With the fix** (`JOIN FETCH` in `OrderRepository`):
```sql
-- Single query for everything
SELECT DISTINCT o.*, n.*
FROM orders o
LEFT JOIN notifications n ON n.order_id = o.id
WHERE o.user_id = ?
ORDER BY o.created_at DESC
```

---

## S3 Error Handling & Fallback

```
Consumer receives order event
         │
         ▼
    Save Order ──► Save Notification (status=SENT)
         │
         ▼
    Upload to S3
    ┌────┴────┐
    │ success │      │  failure (S3 unavailable)
    │         │      │
    ▼         │      ▼
  DONE        │   Set status=PENDING_UPLOAD
              │   (main flow continues!)
              │
              ▼
    @Scheduled every 5 min
    retries PENDING_UPLOAD
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/notifyhub` | PostgreSQL URL |
| `DB_USERNAME` | `notifyhub` | DB username |
| `DB_PASSWORD` | `notifyhub` | DB password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `AWS_S3_ENDPOINT` | `http://localhost:4566` | S3 endpoint (LocalStack) |
| `AWS_S3_BUCKET` | `notifyhub-reports` | S3 bucket name |
| `AWS_REGION` | `us-east-1` | AWS region |
| `AWS_ACCESS_KEY` | `test` | AWS access key |
| `AWS_SECRET_KEY` | `test` | AWS secret key |
| `SERVER_PORT` | `8080` | App port |

---

## Stopping the Stack

```bash
docker compose down -v  # also removes volumes
```
