package com.qrpay.payment;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.common.persistence.BaseEntity;
import com.qrpay.qr.QrSnapshot;
import com.qrpay.qr.QrType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Column(name = "qr_id", nullable = false, updatable = false)
    private UUID qrId;

    @Enumerated(EnumType.STRING)
    @Column(name = "qr_type", nullable = false, updatable = false)
    private QrType qrType;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    /** Denormalised at creation time - a payment is a receipt and must not change if the merchant renames. */
    @Column(name = "merchant_name", nullable = false, updatable = false)
    private String merchantName;

    @Column(name = "payer_user_id", nullable = false, updatable = false)
    private UUID payerUserId;

    @Column(name = "payer_name", nullable = false, updatable = false)
    private String payerName;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "order_ref", updatable = false)
    private String orderRef;

    @Column(updatable = false)
    private String note;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Payment(QrSnapshot qr, UUID payerUserId, String payerName, long amount, String note, String idempotencyKey,
                    Instant expiresAt, Instant now) {
        super(now);
        this.qrId = qr.qrId();
        this.qrType = qr.type();
        this.merchantId = qr.merchantId();
        this.merchantName = qr.merchantName();
        this.orderRef = qr.orderRef();
        this.currency = qr.currency();
        this.payerUserId = payerUserId;
        this.payerName = payerName;
        this.amount = amount;
        this.note = note;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.status = PaymentStatus.CREATED;
        this.updatedAt = now;
    }

    public static Payment create(QrSnapshot qr, UUID payerUserId, String payerName, long amount, String note,
                                 String idempotencyKey, Instant expiresAt, Instant now) {
        return new Payment(qr, payerUserId, payerName, amount, note, idempotencyKey, expiresAt, now);
    }

    void succeed(Instant now) {
        transitionTo(PaymentStatus.SUCCESS, now);
        this.completedAt = now;
    }

    void fail(String reason, Instant now) {
        transitionTo(PaymentStatus.FAILED, now);
        this.failureReason = reason;
        this.completedAt = now;
    }

    void expire(Instant now) {
        transitionTo(PaymentStatus.EXPIRED, now);
        this.failureReason = ErrorCode.PAYMENT_EXPIRED.name();
    }

    void cancel(Instant now) {
        transitionTo(PaymentStatus.CANCELLED, now);
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    private void transitionTo(PaymentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new ApiException(ErrorCode.PAYMENT_INVALID_STATE, "Payment is already " + status);
        }
        this.status = next;
        this.updatedAt = now;
    }
}
