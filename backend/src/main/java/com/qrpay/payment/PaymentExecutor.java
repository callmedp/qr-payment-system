package com.qrpay.payment;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.common.id.UuidV7;
import com.qrpay.config.QrPayProperties;
import com.qrpay.events.PaymentEvent;
import com.qrpay.merchant.Merchant;
import com.qrpay.merchant.MerchantService;
import com.qrpay.outbox.OutboxWriter;
import com.qrpay.payment.PaymentDtos.PaymentView;
import com.qrpay.qr.QrService;
import com.qrpay.wallet.Wallet;
import com.qrpay.wallet.WalletService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of a payment. Everything in {@link #execute} is ONE Postgres transaction:
 *
 * <ol>
 *   <li>lock the payment row and re-check its state (FOR UPDATE)</li>
 *   <li>claim the QR (dynamic: compare-and-set ACTIVE -> PAID)</li>
 *   <li>lock both wallets in id order, debit payer, credit merchant, write 2 ledger rows</li>
 *   <li>payment CREATED -> SUCCESS</li>
 *   <li>append PAYMENT_SUCCEEDED to the outbox</li>
 * </ol>
 *
 * Any failure rolls back all five steps, so money can never move without the payment succeeding (or vice
 * versa), and a dynamic QR can never be marked paid without money moving.
 */
@Component
@RequiredArgsConstructor
public class PaymentExecutor {

    private final PaymentRepository payments;
    private final QrService qrService;
    private final WalletService walletService;
    private final MerchantService merchantService;
    private final OutboxWriter outbox;
    private final QrPayProperties props;

    @Transactional
    public PaymentView execute(UUID paymentId) {
        Payment payment = payments.lockById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return PaymentView.of(payment); // duplicate confirm - already done
        }
        if (payment.getStatus() != PaymentStatus.CREATED) {
            throw new ApiException(ErrorCode.PAYMENT_INVALID_STATE, "Payment is already " + payment.getStatus());
        }
        Instant now = Instant.now();
        if (payment.isExpired(now)) {
            throw new ApiException(ErrorCode.PAYMENT_EXPIRED);
        }

        qrService.claimForPayment(payment.getQrId(), payment.getId());

        Wallet payer = walletService.userWallet(payment.getPayerUserId());
        Wallet payee = walletService.merchantWallet(payment.getMerchantId());
        walletService.transfer(payer.getId(), payee.getId(), payment.getAmount(), payment.getId(),
                "QR payment to " + payment.getMerchantName());

        payment.succeed(now);
        appendEvent(payment, PaymentEvent.SUCCEEDED);
        return PaymentView.of(payment);
    }

    /** Records a business failure in its own transaction (the attempt's transaction was rolled back). */
    @Transactional
    public PaymentView markFailed(UUID paymentId, ErrorCode reason) {
        Payment payment = payments.lockById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (payment.getStatus() != PaymentStatus.CREATED) {
            return PaymentView.of(payment);
        }
        Instant now = Instant.now();
        if (reason == ErrorCode.PAYMENT_EXPIRED) {
            payment.expire(now);
        } else {
            payment.fail(reason.name(), now);
            appendEvent(payment, PaymentEvent.FAILED);
        }
        return PaymentView.of(payment);
    }

    @Transactional
    public PaymentView cancel(UUID paymentId, UUID payerUserId) {
        Payment payment = payments.lockById(paymentId)
                .filter(p -> p.getPayerUserId().equals(payerUserId))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        payment.cancel(Instant.now());
        return PaymentView.of(payment);
    }

    @Transactional
    public int expireStale() {
        return payments.expireStale(Instant.now());
    }

    private void appendEvent(Payment p, String type) {
        Merchant merchant = merchantService.getById(p.getMerchantId());
        UUID eventId = UuidV7.next();
        PaymentEvent event = new PaymentEvent(eventId, type, Instant.now(), p.getId(), p.getStatus().name(),
                p.getFailureReason(), p.getQrId(), p.getQrType().name(), p.getOrderRef(), p.getMerchantId(),
                merchant.getUserId(), p.getMerchantName(), p.getPayerUserId(), p.getPayerName(), p.getAmount(),
                p.getCurrency());
        // key = merchantId -> all events of a merchant are ordered within one partition
        outbox.append(eventId, "Payment", p.getId(), type, props.kafka().paymentEventsTopic(),
                p.getMerchantId().toString(), event);
    }
}
