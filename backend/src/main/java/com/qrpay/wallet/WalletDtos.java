package com.qrpay.wallet;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;

public final class WalletDtos {

    private WalletDtos() {
    }

    public record WalletView(UUID id, OwnerType ownerType, long balance, String currency, Instant updatedAt) {

        static WalletView of(Wallet w) {
            return new WalletView(w.getId(), w.getOwnerType(), w.getBalance(), w.getCurrency(), w.getUpdatedAt());
        }
    }

    public record LedgerEntryView(UUID id, EntryType entryType, long amount, long balanceAfter, String description,
                                  UUID paymentId, Instant createdAt) {

        static LedgerEntryView of(LedgerEntry e) {
            return new LedgerEntryView(e.getId(), e.getEntryType(), e.getAmount(), e.getBalanceAfter(),
                    e.getDescription(), e.getPaymentId(), e.getCreatedAt());
        }
    }

    /** Demo-only: simulates money arriving from a bank / card top-up. Amount in paise. */
    public record TopUpRequest(@Min(100) @Max(10_000_000) long amount) {}

    public record TransferResult(UUID transferId, long payerBalanceAfter, long payeeBalanceAfter) {}
}
