package com.qrpay.webhook;

import com.qrpay.events.PaymentEvent;
import java.time.Instant;
import java.util.UUID;

/** Public, versioned contract sent to merchants. Internal ids (user ids) are intentionally not exposed. */
public record WebhookPayload(UUID id, String type, String apiVersion, Instant createdAt, Data data) {

    public record Data(UUID paymentId, String status, String failureReason, long amount, String currency,
                       UUID qrId, String qrType, String orderRef, String payerName) {}

    static WebhookPayload from(PaymentEvent e) {
        return new WebhookPayload(e.eventId(), e.eventType(), "2026-09-01", e.occurredAt(),
                new Data(e.paymentId(), e.status(), e.failureReason(), e.amount(), e.currency(), e.qrId(),
                        e.qrType(), e.orderRef(), e.payerName()));
    }
}
