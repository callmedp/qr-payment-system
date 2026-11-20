package com.qrpay.qr;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class QrDtos {

    private QrDtos() {
    }

    public record CreateDynamicQrRequest(
            @Positive long amount,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "may contain letters, digits, . _ -") String orderRef,
            @Min(30) @Max(1800) Integer expiresInSeconds) {}

    public record ResolveQrRequest(@NotBlank @Size(max = 1024) String payload) {}

    /** Carries the signed payload only; clients render it into a QR image themselves. */
    public record QrView(UUID id, QrType type, QrStatus status, Long amount, String currency, String orderRef,
                         String payload, Instant expiresAt, UUID paidPaymentId, Instant createdAt) {}

    /** What the customer sees after scanning: who they are paying and how much. */
    public record ResolvedQr(UUID qrId, QrType type, String merchantName, String merchantVpa, Long amount,
                             String currency, String orderRef, Instant expiresAt) {}
}
