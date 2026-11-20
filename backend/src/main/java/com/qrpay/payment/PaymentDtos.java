package com.qrpay.payment;

import com.qrpay.qr.QrType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** {@code amount} is required for STATIC codes and must be omitted or equal for DYNAMIC codes. */
    public record InitiatePaymentRequest(@NotNull UUID qrId, @Positive Long amount, @Size(max = 140) String note) {}

    public record ConfirmPaymentRequest(@NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin) {}

    public record PaymentView(UUID id, UUID qrId, QrType qrType, UUID merchantId, String merchantName, String payerName,
                              long amount, String currency, PaymentStatus status, String failureReason, String orderRef,
                              String note, Instant createdAt, Instant expiresAt, Instant completedAt) {

        static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getQrId(), p.getQrType(), p.getMerchantId(), p.getMerchantName(),
                    p.getPayerName(), p.getAmount(), p.getCurrency(), p.getStatus(), p.getFailureReason(),
                    p.getOrderRef(), p.getNote(), p.getCreatedAt(), p.getExpiresAt(), p.getCompletedAt());
        }
    }
}
