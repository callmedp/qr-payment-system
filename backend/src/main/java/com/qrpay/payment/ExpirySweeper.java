package com.qrpay.payment;

import com.qrpay.common.lock.DistributedLock;
import com.qrpay.qr.QrService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves stale CREATED payments to EXPIRED and overdue dynamic QRs to EXPIRED. Runs on every node but the
 * Redis lock makes only one node do the work per tick. Correctness does not depend on this job: confirm
 * re-checks expiry inside its transaction, the job just keeps statuses tidy for dashboards.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpirySweeper {

    private final PaymentExecutor executor;
    private final QrService qrService;
    private final DistributedLock lock;

    @Scheduled(fixedDelayString = "${qrpay.jobs.expiry-interval-ms}", initialDelay = 5000)
    public void sweep() {
        lock.tryAcquire("job:expiry-sweeper", Duration.ofSeconds(30)).ifPresent(lease -> {
            try (lease) {
                int payments = executor.expireStale();
                int qrCodes = qrService.expireOverdue();
                if (payments + qrCodes > 0) {
                    log.info("Expiry sweep: {} payment(s), {} QR code(s) expired", payments, qrCodes);
                }
            }
        });
    }
}
