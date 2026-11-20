package com.qrpay.wallet;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
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
@Table(name = "wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet extends BaseEntity {

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, updatable = false)
    private OwnerType ownerType;

    /** Minor units (paise). */
    @Column(nullable = false)
    private long balance;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "pin_hash")
    private String pinHash;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Wallet(UUID ownerId, OwnerType ownerType, String currency, String pinHash, Instant now) {
        super(now);
        this.ownerId = ownerId;
        this.ownerType = ownerType;
        this.currency = currency;
        this.pinHash = pinHash;
        this.balance = 0;
        this.updatedAt = now;
    }

    public static Wallet open(UUID ownerId, OwnerType ownerType, String currency, String pinHash) {
        return new Wallet(ownerId, ownerType, currency, pinHash, Instant.now());
    }

    /** Callers must hold a row lock (see {@link WalletRepository#lockByIdsInOrder}). */
    void debit(long amount, Instant now) {
        if (ownerType != OwnerType.SYSTEM && balance < amount) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        balance = Math.subtractExact(balance, amount);
        updatedAt = now;
    }

    void credit(long amount, Instant now) {
        balance = Math.addExact(balance, amount);
        updatedAt = now;
    }
}
