package com.qrpay.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByPayerUserIdAndIdempotencyKey(UUID payerUserId, String idempotencyKey);

    /** SELECT ... FOR UPDATE: serialises confirm / cancel / expiry on the same payment row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    Page<Payment> findByPayerUserIdOrderByCreatedAtDesc(UUID payerUserId, Pageable pageable);

    Page<Payment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    @Modifying
    @Query("""
            update Payment p set p.status = :expired, p.failureReason = 'PAYMENT_EXPIRED',
                                 p.updatedAt = :now, p.version = p.version + 1
            where p.status = :created and p.expiresAt <= :now
            """)
    int bulkExpire(@Param("now") Instant now, @Param("created") PaymentStatus created,
                   @Param("expired") PaymentStatus expired);

    default int expireStale(Instant now) {
        return bulkExpire(now, PaymentStatus.CREATED, PaymentStatus.EXPIRED);
    }
}
