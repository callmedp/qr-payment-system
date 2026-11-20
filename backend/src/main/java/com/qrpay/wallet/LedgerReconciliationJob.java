package com.qrpay.wallet;

import com.qrpay.common.lock.DistributedLock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Continuously proves the two ledger invariants and exports them as metrics (alert if either is non-zero):
 * <ol>
 *   <li>{@code SUM(wallets.balance) == 0} - money is neither created nor destroyed (SYSTEM wallet is the counterweight)</li>
 *   <li>every wallet's balance equals SUM(credits) - SUM(debits) of its ledger entries</li>
 * </ol>
 * In production this runs against a read replica / the warehouse, not the primary.
 */
@Slf4j
@Component
public class LedgerReconciliationJob {

    private final JdbcTemplate jdbc;
    private final DistributedLock lock;
    private final AtomicLong netBalance = new AtomicLong();
    private final AtomicLong mismatchedWallets = new AtomicLong();

    public LedgerReconciliationJob(JdbcTemplate jdbc, DistributedLock lock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.lock = lock;
        Gauge.builder("qrpay.ledger.net_balance", netBalance, AtomicLong::get)
                .description("Sum of all wallet balances; must be 0").register(meters);
        Gauge.builder("qrpay.ledger.mismatched_wallets", mismatchedWallets, AtomicLong::get)
                .description("Wallets whose balance differs from their ledger").register(meters);
    }

    @Scheduled(fixedDelayString = "${qrpay.jobs.reconciliation-interval-ms}", initialDelay = 30_000)
    public void reconcile() {
        lock.tryAcquire("job:ledger-reconciliation", Duration.ofMinutes(5)).ifPresent(lease -> {
            try (lease) {
                Long net = jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM wallets", Long.class);
                Long mismatched = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM wallets w
                        LEFT JOIN (
                            SELECT wallet_id,
                                   SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
                            FROM ledger_entries GROUP BY wallet_id
                        ) l ON l.wallet_id = w.id
                        WHERE w.balance <> COALESCE(l.net, 0)
                        """, Long.class);
                netBalance.set(net == null ? 0 : net);
                mismatchedWallets.set(mismatched == null ? 0 : mismatched);
                if (netBalance.get() != 0 || mismatchedWallets.get() != 0) {
                    log.error("LEDGER INVARIANT BROKEN: net balance {} , mismatched wallets {}", net, mismatched);
                } else {
                    log.info("Ledger reconciliation OK");
                }
            }
        });
    }
}
