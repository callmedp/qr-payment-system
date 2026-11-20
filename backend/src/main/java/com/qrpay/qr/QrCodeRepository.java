package com.qrpay.qr;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QrCodeRepository extends JpaRepository<QrCode, UUID> {

    Optional<QrCode> findByMerchantIdAndType(UUID merchantId, QrType type);

    Optional<QrCode> findByMerchantIdAndOrderRef(UUID merchantId, String orderRef);

    List<QrCode> findTop20ByMerchantIdAndTypeOrderByCreatedAtDesc(UUID merchantId, QrType type);

    /**
     * Compare-and-set ACTIVE -> PAID. Returns 1 for exactly one caller even under concurrency, because
     * Postgres re-evaluates the WHERE clause after acquiring the row lock (READ COMMITTED semantics).
     */
    @Modifying
    @Query("""
            update QrCode q set q.status = :paid, q.paidPaymentId = :paymentId, q.version = q.version + 1
            where q.id = :id and q.type = :dynamic and q.status = :active and q.expiresAt > :now
            """)
    int compareAndSetPaid(@Param("id") UUID id, @Param("paymentId") UUID paymentId, @Param("now") Instant now,
                          @Param("paid") QrStatus paid, @Param("active") QrStatus active,
                          @Param("dynamic") QrType dynamic);

    default int markPaidIfActive(UUID id, UUID paymentId, Instant now) {
        return compareAndSetPaid(id, paymentId, now, QrStatus.PAID, QrStatus.ACTIVE, QrType.DYNAMIC);
    }

    @Modifying
    @Query("""
            update QrCode q set q.status = :expired, q.version = q.version + 1
            where q.type = :dynamic and q.status = :active and q.expiresAt <= :now
            """)
    int bulkExpire(@Param("now") Instant now, @Param("expired") QrStatus expired,
                   @Param("active") QrStatus active, @Param("dynamic") QrType dynamic);

    default int expireOverdue(Instant now) {
        return bulkExpire(now, QrStatus.EXPIRED, QrStatus.ACTIVE, QrType.DYNAMIC);
    }
}
