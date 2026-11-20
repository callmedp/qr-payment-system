package com.qrpay.payment;

import com.qrpay.common.idempotency.IdempotencyService;
import com.qrpay.common.ratelimit.RateLimited;
import com.qrpay.common.web.PageResponse;
import com.qrpay.payment.PaymentDtos.ConfirmPaymentRequest;
import com.qrpay.payment.PaymentDtos.InitiatePaymentRequest;
import com.qrpay.payment.PaymentDtos.PaymentView;
import com.qrpay.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    @Operation(summary = "Step 1 - create a payment for a scanned QR (no money moves yet)")
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @RateLimited("payments")
    public ResponseEntity<PaymentView> initiate(@AuthenticationPrincipal AuthUser user,
                                                @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                @Valid @RequestBody InitiatePaymentRequest request) {
        var result = idempotency.execute("payment-initiate", user.userId(), idempotencyKey, request, PaymentView.class,
                () -> paymentService.initiate(user, request, idempotencyKey));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(result.body());
    }

    @Operation(summary = "Step 2 - authorise with PIN; debits payer and credits merchant atomically")
    @PostMapping("/{paymentId}/confirm")
    @PreAuthorize("hasRole('CUSTOMER')")
    @RateLimited("payments")
    public ResponseEntity<PaymentView> confirm(@AuthenticationPrincipal AuthUser user,
                                               @PathVariable UUID paymentId,
                                               @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                               @Valid @RequestBody ConfirmPaymentRequest request) {
        // the fingerprint deliberately excludes the PIN: a 4-6 digit secret must never be persisted, even hashed
        var fingerprint = Map.of("paymentId", paymentId);
        var result = idempotency.execute("payment-confirm", user.userId(), idempotencyKey, fingerprint,
                PaymentView.class, () -> paymentService.confirm(user, paymentId, request.pin()));
        return ResponseEntity.ok().header(REPLAYED_HEADER, String.valueOf(result.replayed())).body(result.body());
    }

    @Operation(summary = "Abandon a payment that has not been confirmed yet")
    @PostMapping("/{paymentId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PaymentView cancel(@AuthenticationPrincipal AuthUser user, @PathVariable UUID paymentId) {
        return paymentService.cancel(user, paymentId);
    }

    @Operation(summary = "Payment details (visible to the payer and the receiving merchant)")
    @GetMapping("/{paymentId}")
    public PaymentView get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID paymentId) {
        return paymentService.get(user, paymentId);
    }

    @Operation(summary = "Payment history - sent (customer) or received (merchant), newest first")
    @GetMapping
    public PageResponse<PaymentView> list(@AuthenticationPrincipal AuthUser user,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return paymentService.list(user, page, size);
    }
}
