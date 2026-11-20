package com.qrpay.qr;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.config.QrPayProperties;
import com.qrpay.merchant.Merchant;
import com.qrpay.merchant.MerchantService;
import com.qrpay.qr.QrDtos.CreateDynamicQrRequest;
import com.qrpay.qr.QrDtos.QrView;
import com.qrpay.qr.QrDtos.ResolvedQr;
import com.qrpay.qr.QrPayloadCodec.QrPayload;
import com.qrpay.security.AuthUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class QrService {

    private final QrCodeRepository qrCodes;
    private final MerchantService merchantService;
    private final QrPayloadCodec codec;
    private final QrCache cache;
    private final QrPayProperties props;

    // ---------------------------------------------------------------- merchant side

    /**
     * Creates a single-use, fixed-amount code. If {@code orderRef} is supplied the call is naturally
     * idempotent: retrying with the same order + amount returns the existing live code.
     */
    @Transactional
    public QrView createDynamic(AuthUser principal, CreateDynamicQrRequest request) {
        Merchant merchant = activeMerchant(principal);
        if (request.amount() > props.payment().maxAmount()) {
            throw new ApiException(ErrorCode.AMOUNT_LIMIT_EXCEEDED);
        }
        String orderRef = blankToNull(request.orderRef());
        if (orderRef != null) {
            var existing = qrCodes.findByMerchantIdAndOrderRef(merchant.getId(), orderRef);
            if (existing.isPresent()) {
                QrCode qr = existing.get();
                if (qr.getStatus() == QrStatus.ACTIVE && Objects.equals(qr.getAmount(), request.amount())
                        && !qr.isExpired(Instant.now())) {
                    return toView(qr);
                }
                throw new ApiException(ErrorCode.DUPLICATE_ORDER_REF);
            }
        }
        Duration ttl = request.expiresInSeconds() == null
                ? props.qr().defaultExpiry()
                : Duration.ofSeconds(request.expiresInSeconds());
        if (ttl.compareTo(props.qr().maxExpiry()) > 0) {
            throw new ApiException(ErrorCode.INVALID_EXPIRY);
        }

        Instant now = Instant.now();
        QrCode qr = QrCode.dynamic(merchant.getId(), request.amount(), props.payment().currency(), orderRef, now.plus(ttl), now);
        qr.attachPayload(codec.encode(new QrPayload(qr.getId())));
        try {
            // Flush inside the transaction: the orderRef pre-check above is not atomic, so two
            // concurrent first-time calls both reach here and one loses ux_qr_order_ref. A plain
            // save() would defer that INSERT to commit, where this catch can no longer see it.
            return toView(qrCodes.saveAndFlush(qr));
        } catch (DataIntegrityViolationException race) {
            if (orderRef == null) {
                throw race; // no unique index to race on; a violation here is a genuine bug
            }
            // The transaction is rollback-only, so we cannot re-read the winner - and we cannot tell
            // a concurrent idempotent retry from a real duplicate. Retrying resolves both correctly.
            throw new ApiException(ErrorCode.CONFLICT, "QR code for this order is being created, retry");
        }
    }

    /** Every merchant has exactly one static code (enforced by a partial unique index). */
    @Transactional
    public QrView getOrCreateStatic(AuthUser principal) {
        Merchant merchant = activeMerchant(principal);
        QrCode qr = qrCodes.findByMerchantIdAndType(merchant.getId(), QrType.STATIC).orElseGet(() -> {
            QrCode created = QrCode.staticCode(merchant.getId(), props.payment().currency(), Instant.now());
            created.attachPayload(codec.encode(new QrPayload(created.getId())));
            try {
                return qrCodes.saveAndFlush(created);
            } catch (DataIntegrityViolationException race) {
                throw new ApiException(ErrorCode.CONFLICT, "Static QR is being created, retry");
            }
        });
        return toView(qr);
    }

    @Transactional(readOnly = true)
    public QrView get(AuthUser principal, UUID qrId) {
        return toView(ownedBy(principal, qrId));
    }

    @Transactional(readOnly = true)
    public List<QrView> recentDynamic(AuthUser principal) {
        return qrCodes.findTop20ByMerchantIdAndTypeOrderByCreatedAtDesc(principal.merchantId(), QrType.DYNAMIC)
                .stream().map(this::toView).toList();
    }

    // ---------------------------------------------------------------- customer side

    /** Scan -> verify signature -> look up live state (Redis first) -> show payee + amount. */
    public ResolvedQr resolve(String rawPayload) {
        QrSnapshot snapshot = payableSnapshot(codec.decodeAndVerify(rawPayload.trim()).qrId());
        return new ResolvedQr(snapshot.qrId(), snapshot.type(), snapshot.merchantName(), snapshot.merchantVpa(),
                snapshot.amount(), snapshot.currency(), snapshot.orderRef(), snapshot.expiresAt());
    }

    /** Snapshot that is guaranteed payable <em>at read time</em>; the payment transaction re-checks. */
    public QrSnapshot payableSnapshot(UUID qrId) {
        QrSnapshot snapshot = snapshot(qrId);
        if (!snapshot.merchantActive()) {
            throw new ApiException(ErrorCode.QR_INACTIVE, "Merchant is not accepting payments");
        }
        switch (snapshot.status()) {
            case PAID -> throw new ApiException(ErrorCode.QR_ALREADY_PAID);
            case EXPIRED -> throw new ApiException(ErrorCode.QR_EXPIRED);
            case DISABLED -> throw new ApiException(ErrorCode.QR_INACTIVE);
            case ACTIVE -> {
                if (snapshot.isExpired(Instant.now())) {
                    throw new ApiException(ErrorCode.QR_EXPIRED);
                }
            }
        }
        return snapshot;
    }

    public QrSnapshot snapshot(UUID qrId) {
        return cache.get(qrId).orElseGet(() -> {
            QrCode qr = qrCodes.findById(qrId).orElseThrow(() -> ApiException.notFound("QR code"));
            Merchant merchant = merchantService.getById(qr.getMerchantId());
            QrSnapshot snapshot = new QrSnapshot(qr.getId(), qr.getType(), qr.getStatus(), qr.getAmount(),
                    qr.getCurrency(), qr.getOrderRef(), qr.getExpiresAt(), merchant.getId(), merchant.getUserId(),
                    merchant.getBusinessName(), merchant.getVpa(), merchant.isActive());
            cache.put(snapshot);
            return snapshot;
        });
    }

    // ---------------------------------------------------------------- payment integration

    /**
     * Called inside the payment transaction. For a dynamic code this is the atomic single-use claim
     * (ACTIVE -> PAID); if the surrounding transaction rolls back (e.g. insufficient balance) the claim
     * rolls back with it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claimForPayment(UUID qrId, UUID paymentId) {
        QrCode qr = qrCodes.findById(qrId).orElseThrow(() -> ApiException.notFound("QR code"));
        if (qr.getType() == QrType.STATIC) {
            if (qr.getStatus() != QrStatus.ACTIVE) {
                throw new ApiException(ErrorCode.QR_INACTIVE);
            }
            return;
        }
        Instant now = Instant.now();
        if (qrCodes.markPaidIfActive(qrId, paymentId, now) == 0) {
            // lost the race or the code is no longer live; the entity we loaded tells us why
            if (qr.getStatus() == QrStatus.PAID || qr.getStatus() == QrStatus.ACTIVE && !qr.isExpired(now)) {
                throw new ApiException(ErrorCode.QR_ALREADY_PAID);
            }
            throw new ApiException(qr.isExpired(now) || qr.getStatus() == QrStatus.EXPIRED
                    ? ErrorCode.QR_EXPIRED : ErrorCode.QR_INACTIVE);
        }
        evictAfterCommit(qrId);
    }

    @Transactional
    public int expireOverdue() {
        return qrCodes.expireOverdue(Instant.now());
    }

    // ---------------------------------------------------------------- helpers

    private Merchant activeMerchant(AuthUser principal) {
        Merchant merchant = merchantService.getById(principal.merchantId());
        if (!merchant.isActive()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Merchant account is suspended");
        }
        return merchant;
    }

    private QrCode ownedBy(AuthUser principal, UUID qrId) {
        QrCode qr = qrCodes.findById(qrId).orElseThrow(() -> ApiException.notFound("QR code"));
        if (!qr.getMerchantId().equals(principal.merchantId())) {
            throw ApiException.notFound("QR code"); // don't reveal other merchants' ids
        }
        return qr;
    }

    private QrView toView(QrCode qr) {
        return new QrView(qr.getId(), qr.getType(), qr.getStatus(), qr.getAmount(), qr.getCurrency(), qr.getOrderRef(),
                qr.getPayload(), qr.getExpiresAt(), qr.getPaidPaymentId(), qr.getCreatedAt());
    }

    private void evictAfterCommit(UUID qrId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict(qrId);
            }
        });
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
