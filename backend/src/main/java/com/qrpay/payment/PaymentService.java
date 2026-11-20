package com.qrpay.payment;

import com.qrpay.auth.UserAccount;
import com.qrpay.auth.UserRepository;
import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.common.lock.DistributedLock;
import com.qrpay.common.web.PageResponse;
import com.qrpay.config.QrPayProperties;
import com.qrpay.payment.PaymentDtos.InitiatePaymentRequest;
import com.qrpay.payment.PaymentDtos.PaymentView;
import com.qrpay.qr.QrService;
import com.qrpay.qr.QrSnapshot;
import com.qrpay.qr.QrType;
import com.qrpay.security.AuthUser;
import com.qrpay.wallet.WalletService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the two-step payment flow:
 *
 * <pre>
 *   initiate : validate QR + amount, create payment (CREATED)       - cheap, no money moves
 *   confirm  : verify PIN -> Redis lock -> PaymentExecutor (1 DB tx) -> SUCCESS | FAILED
 * </pre>
 *
 * Deliberately NOT transactional at this level: the PIN check (BCrypt) and the distributed lock must sit
 * <em>outside</em> the DB transaction so row locks are held for milliseconds, not for the whole request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** Business outcomes that end the payment as FAILED rather than surfacing as a request error. */
    private static final Set<ErrorCode> TERMINAL_FAILURES = EnumSet.of(
            ErrorCode.INSUFFICIENT_BALANCE, ErrorCode.QR_ALREADY_PAID, ErrorCode.QR_EXPIRED,
            ErrorCode.QR_INACTIVE, ErrorCode.PAYMENT_EXPIRED);

    private final PaymentRepository payments;
    private final PaymentExecutor executor;
    private final QrService qrService;
    private final WalletService walletService;
    private final UserRepository users;
    private final DistributedLock lock;
    private final QrPayProperties props;

    @Transactional
    public PaymentView initiate(AuthUser payer, InitiatePaymentRequest request, String idempotencyKey) {
        var existing = payments.findByPayerUserIdAndIdempotencyKey(payer.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return PaymentView.of(existing.get()); // durable idempotency, works even if Redis lost the key
        }
        QrSnapshot qr = qrService.payableSnapshot(request.qrId());
        long amount = resolveAmount(qr, request.amount());
        UserAccount user = users.findById(payer.userId()).orElseThrow(() -> ApiException.notFound("User"));

        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.payment().pendingTtl());
        if (qr.expiresAt() != null && qr.expiresAt().isBefore(expiresAt)) {
            expiresAt = qr.expiresAt(); // a payment session never outlives its QR
        }
        Payment payment = Payment.create(qr, payer.userId(), user.getFullName(), amount, trimToNull(request.note()),
                idempotencyKey, expiresAt, now);
        return PaymentView.of(payments.save(payment));
    }

    public PaymentView confirm(AuthUser payer, UUID paymentId, String pin) {
        Payment current = payments.findById(paymentId)
                .filter(p -> p.getPayerUserId().equals(payer.userId()))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (current.getStatus() == PaymentStatus.SUCCESS) {
            return PaymentView.of(current);
        }
        if (current.getStatus() != PaymentStatus.CREATED) {
            throw new ApiException(ErrorCode.PAYMENT_INVALID_STATE, "Payment is already " + current.getStatus());
        }

        walletService.verifyPin(payer.userId(), pin);

        // A dynamic QR is single-use: serialise all payers of that QR. A static QR has many concurrent
        // payers, so we only guard against double-submits of the same payment.
        String lockName = current.getQrType() == QrType.DYNAMIC ? "qr:" + current.getQrId() : "payment:" + paymentId;
        try (DistributedLock.Lease ignored = lock.tryAcquire(lockName, props.payment().lockTtl())
                .orElseThrow(() -> new ApiException(ErrorCode.CONCURRENT_MODIFICATION))) {
            try {
                PaymentView result = executor.execute(paymentId);
                log.info("Payment {} succeeded: {} {} to merchant {}", paymentId, result.amount(), result.currency(),
                        result.merchantId());
                return result;
            } catch (ApiException e) {
                if (TERMINAL_FAILURES.contains(e.code())) {
                    log.info("Payment {} failed: {}", paymentId, e.code());
                    return executor.markFailed(paymentId, e.code());
                }
                throw e;
            }
        }
    }

    public PaymentView cancel(AuthUser payer, UUID paymentId) {
        return executor.cancel(paymentId, payer.userId());
    }

    @Transactional(readOnly = true)
    public PaymentView get(AuthUser principal, UUID paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        boolean visible = principal.isMerchant()
                ? payment.getMerchantId().equals(principal.merchantId())
                : payment.getPayerUserId().equals(principal.userId());
        if (!visible) {
            throw ApiException.notFound("Payment");
        }
        return PaymentView.of(payment);
    }

    /** Customers see what they paid, merchants see what they received. */
    @Transactional(readOnly = true)
    public PageResponse<PaymentView> list(AuthUser principal, int page, int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        var result = principal.isMerchant()
                ? payments.findByMerchantIdOrderByCreatedAtDesc(principal.merchantId(), pageable)
                : payments.findByPayerUserIdOrderByCreatedAtDesc(principal.userId(), pageable);
        return PageResponse.from(result.map(PaymentView::of));
    }

    private long resolveAmount(QrSnapshot qr, Long requested) {
        long amount;
        if (qr.type() == QrType.DYNAMIC) {
            if (requested != null && !requested.equals(qr.amount())) {
                throw new ApiException(ErrorCode.AMOUNT_MISMATCH);
            }
            amount = qr.amount(); // the server-side amount wins, never the client's
        } else {
            if (requested == null) {
                throw new ApiException(ErrorCode.AMOUNT_REQUIRED);
            }
            amount = requested;
        }
        if (amount > props.payment().maxAmount()) {
            throw new ApiException(ErrorCode.AMOUNT_LIMIT_EXCEEDED);
        }
        return amount;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
