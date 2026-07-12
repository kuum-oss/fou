package com.notifyhub.service;

/**
 * Thrown when the S3 report for an order has not been uploaded yet
 * (notification is in PENDING_UPLOAD state). Client should retry later.
 */
public class ReportNotReadyException extends RuntimeException {

    public ReportNotReadyException(String message) {
        super(message);
    }
}
