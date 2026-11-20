package com.qrpay.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Integration event published to Kafka topic {@code payment.events}, keyed by merchantId so all events
 * of one merchant land on the same partition (per-merchant ordering). Consumers must be idempotent on
 * {@link #eventId()} because delivery is at-least-once.
 */
public record PaymentEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID paymentId,
        String status,
        String failureReason,
        UUID qrId,
        String qrType,
        String orderRef,
        UUID merchantId,
        UUID merchantUserId,
        String merchantName,
        UUID payerUserId,
        String payerName,
        long amount,
        String currency) {

    public static final String SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String FAILED = "PAYMENT_FAILED";
}
