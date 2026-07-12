package com.notifyhub.service;

import com.notifyhub.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Service responsible for generating order reports and uploading them to AWS S3 (LocalStack in dev).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3ReportService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.presigned-url-expiration-minutes}")
    private long presignedUrlExpirationMinutes;

    /**
     * Generates a plain-text report for the given order and uploads it to S3.
     * Creates the bucket if it does not exist (useful for LocalStack cold start).
     *
     * @param order the order to report on
     */
    public void uploadReport(Order order) {
        String reportContent = buildReportContent(order);
        String key = buildReportKey(order.getId());

        ensureBucketExists();

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("text/plain")
                .build();

        s3Client.putObject(putRequest, RequestBody.fromString(reportContent));
        log.info("[S3ReportService] Uploaded report to S3: bucket='{}', key='{}'", bucketName, key);
    }

    /**
     * Generates a presigned URL for downloading the order report.
     * The URL is valid for the configured expiration period (default: 15 minutes).
     *
     * @param orderId the order UUID
     * @return presigned URL string
     */
    public String generatePresignedUrl(UUID orderId) {
        String key = buildReportKey(orderId);
        log.info("[S3ReportService] Generating presigned URL: bucket='{}', key='{}', ttl={}min",
                bucketName, key, presignedUrlExpirationMinutes);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignedUrlExpirationMinutes))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        String url = presigned.url().toString();
        log.debug("[S3ReportService] Generated presigned URL: {}", url);
        return url;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────────

    private String buildReportKey(UUID orderId) {
        return String.format("order_%s_report.txt", orderId);
    }

    private String buildReportContent(Order order) {
        return String.format("""
                ========================================
                        ORDER REPORT — NotifyHub
                ========================================
                Order ID   : %s
                User ID    : %s
                Amount     : %.2f
                Status     : %s
                Created At : %s
                ========================================
                Report generated automatically by NotifyHub.
                """,
                order.getId(),
                order.getUserId(),
                order.getAmount(),
                order.getStatus(),
                order.getCreatedAt() != null ? order.getCreatedAt().format(FORMATTER) : "N/A"
        );
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            log.debug("[S3ReportService] Bucket '{}' already exists", bucketName);
        } catch (NoSuchBucketException e) {
            log.info("[S3ReportService] Bucket '{}' not found, creating it...", bucketName);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            log.info("[S3ReportService] Created bucket '{}'", bucketName);
        }
    }
}
