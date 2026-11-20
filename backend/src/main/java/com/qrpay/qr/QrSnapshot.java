package com.qrpay.qr;

import java.time.Instant;
import java.util.UUID;

/** Read-model of a QR code + its merchant, cached in Redis for the hot "scan -> resolve" path. */
public record QrSnapshot(
        UUID qrId,
        QrType type,
        QrStatus status,
        Long amount,
        String currency,
        String orderRef,
        Instant expiresAt,
        UUID merchantId,
        UUID merchantUserId,
        String merchantName,
        String merchantVpa,
        boolean merchantActive) {

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
