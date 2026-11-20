package com.qrpay.wallet;

import com.qrpay.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One side of a double-entry transfer. Rows are never updated or deleted. */
@Entity
@Immutable
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry extends BaseEntity {

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    private String description;

    private LedgerEntry(UUID walletId, UUID transferId, UUID paymentId, EntryType entryType, long amount,
                        long balanceAfter, String description, Instant now) {
        super(now);
        this.walletId = walletId;
        this.transferId = transferId;
        this.paymentId = paymentId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
    }

    static LedgerEntry of(Wallet wallet, UUID transferId, UUID paymentId, EntryType type, long amount,
                          String description, Instant now) {
        return new LedgerEntry(wallet.getId(), transferId, paymentId, type, amount, wallet.getBalance(), description, now);
    }
}
