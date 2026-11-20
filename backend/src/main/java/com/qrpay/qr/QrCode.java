package com.qrpay.qr;

import com.qrpay.common.persistence.BaseEntity;
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
@Table(name = "qr_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QrCode extends BaseEntity {

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private QrType type;

    /** Minor units; null for static codes. */
    private Long amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "order_ref")
    private String orderRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QrStatus status;

    @Column(nullable = false)
    private String payload;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_payment_id")
    private UUID paidPaymentId;

    @Version
    private Long version;

    private QrCode(UUID merchantId, QrType type, Long amount, String currency, String orderRef, Instant expiresAt,
                   Instant now) {
        super(now);
        this.merchantId = merchantId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.orderRef = orderRef;
        this.expiresAt = expiresAt;
        this.status = QrStatus.ACTIVE;
    }

    public static QrCode dynamic(UUID merchantId, long amount, String currency, String orderRef, Instant expiresAt,
                                 Instant now) {
        return new QrCode(merchantId, QrType.DYNAMIC, amount, currency, orderRef, expiresAt, now);
    }

    public static QrCode staticCode(UUID merchantId, String currency, Instant now) {
        return new QrCode(merchantId, QrType.STATIC, null, currency, null, null, now);
    }

    /** The payload embeds the id, so it can only be attached once the id exists. */
    void attachPayload(String payload) {
        this.payload = payload;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
