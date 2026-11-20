package com.qrpay.wallet;

import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.common.id.UuidV7;
import com.qrpay.common.web.PageResponse;
import com.qrpay.config.QrPayProperties;
import com.qrpay.security.AuthUser;
import com.qrpay.wallet.WalletDtos.LedgerEntryView;
import com.qrpay.wallet.WalletDtos.TransferResult;
import com.qrpay.wallet.WalletDtos.WalletView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallet + double-entry ledger. The only way balances change is {@link #transfer}, which always writes
 * a matching DEBIT/CREDIT pair inside the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    public static final UUID SYSTEM_WALLET_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledger;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final QrPayProperties props;

    @Transactional
    public Wallet openUserWallet(UUID userId, String rawPin) {
        return wallets.save(Wallet.open(userId, OwnerType.USER, currency(), passwordEncoder.encode(rawPin)));
    }

    @Transactional
    public Wallet openMerchantWallet(UUID merchantId) {
        return wallets.save(Wallet.open(merchantId, OwnerType.MERCHANT, currency(), null));
    }

    @Transactional(readOnly = true)
    public Wallet userWallet(UUID userId) {
        return wallets.findByOwnerTypeAndOwnerIdAndCurrency(OwnerType.USER, userId, currency())
                .orElseThrow(() -> ApiException.notFound("Wallet"));
    }

    @Transactional(readOnly = true)
    public Wallet merchantWallet(UUID merchantId) {
        return wallets.findByOwnerTypeAndOwnerIdAndCurrency(OwnerType.MERCHANT, merchantId, currency())
                .orElseThrow(() -> ApiException.notFound("Merchant wallet"));
    }

    /**
     * Atomically moves {@code amount} between two wallets. MANDATORY propagation: a transfer is only
     * meaningful as part of a larger unit of work (payment state change + outbox event) that commits
     * or rolls back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferResult transfer(UUID fromWalletId, UUID toWalletId, long amount, UUID paymentId, String description) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        if (fromWalletId.equals(toWalletId)) {
            throw new IllegalArgumentException("Cannot transfer to the same wallet");
        }
        Map<UUID, Wallet> locked = wallets.lockByIdsInOrder(List.of(fromWalletId, toWalletId)).stream()
                .collect(Collectors.toMap(Wallet::getId, Function.identity()));
        Wallet from = locked.get(fromWalletId);
        Wallet to = locked.get(toWalletId);
        if (from == null || to == null) {
            throw ApiException.notFound("Wallet");
        }
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new IllegalStateException("Cross-currency transfers are not supported");
        }

        Instant now = Instant.now();
        from.debit(amount, now);
        to.credit(amount, now);
        UUID transferId = UuidV7.next();
        ledger.saveAll(List.of(
                LedgerEntry.of(from, transferId, paymentId, EntryType.DEBIT, amount, description, now),
                LedgerEntry.of(to, transferId, paymentId, EntryType.CREDIT, amount, description, now)));
        return new TransferResult(transferId, from.getBalance(), to.getBalance());
    }

    /** Demo top-up: in production this is driven by a bank/payment-gateway settlement callback. */
    @Transactional
    public void topUp(UUID walletId, long amount, String description) {
        transfer(SYSTEM_WALLET_ID, walletId, amount, null, description);
    }

    /**
     * Verifies the wallet PIN with a Redis-backed brute-force guard (N failures -> locked for a window).
     * Runs <em>before</em> any DB transaction or lock so the ~100 ms BCrypt check never holds a row lock.
     */
    public void verifyPin(UUID userId, String rawPin) {
        String key = "pin-fail:" + userId;
        int maxAttempts = props.security().pinMaxAttempts();
        long failures = readCounter(key);
        if (failures >= maxAttempts) {
            throw new ApiException(ErrorCode.PIN_LOCKED);
        }
        Wallet wallet = userWallet(userId);
        if (rawPin == null || !passwordEncoder.matches(rawPin, wallet.getPinHash())) {
            long now = incrementCounter(key);
            long left = Math.max(0, maxAttempts - now);
            throw new ApiException(left == 0 ? ErrorCode.PIN_LOCKED : ErrorCode.INVALID_PIN,
                    left == 0 ? ErrorCode.PIN_LOCKED.defaultMessage() : "Incorrect PIN, " + left + " attempt(s) left");
        }
        try {
            redis.delete(key);
        } catch (DataAccessException ignored) {
            // counter simply expires
        }
    }

    @Transactional(readOnly = true)
    public WalletView myWallet(AuthUser principal) {
        return WalletView.of(walletOf(principal));
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryView> myLedger(AuthUser principal, int page, int size) {
        Wallet wallet = walletOf(principal);
        return PageResponse.from(ledger.findByWalletIdOrderByCreatedAtDesc(wallet.getId(),
                PageRequest.of(page, Math.min(size, 100))).map(LedgerEntryView::of));
    }

    @Transactional
    public WalletView topUpMine(AuthUser principal, long amount) {
        Wallet wallet = walletOf(principal);
        topUp(wallet.getId(), amount, "Wallet top-up");
        return WalletView.of(wallet);
    }

    private Wallet walletOf(AuthUser principal) {
        return principal.isMerchant() ? merchantWallet(principal.merchantId()) : userWallet(principal.userId());
    }

    private String currency() {
        return props.payment().currency();
    }

    private long readCounter(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (DataAccessException e) {
            log.warn("PIN attempt counter unavailable");
            return 0;
        }
    }

    private long incrementCounter(String key) {
        try {
            Long value = redis.opsForValue().increment(key);
            redis.expire(key, props.security().pinLockout());
            return value == null ? 1 : value;
        } catch (DataAccessException e) {
            return 1;
        }
    }
}
