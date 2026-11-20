package com.qrpay.payment;

/**
 * <pre>
 *            confirm OK               insufficient funds / QR taken / QR expired
 *   CREATED ───────────► SUCCESS     CREATED ───────────► FAILED
 *      │ │
 *      │ └── customer cancels ─────► CANCELLED
 *      └──── pending TTL elapses ──► EXPIRED
 * </pre>
 * All states except CREATED are terminal. A wrong PIN does not change state (the customer may retry
 * until the PIN guard locks them out or the session expires).
 */
public enum PaymentStatus {
    CREATED,
    SUCCESS,
    FAILED,
    EXPIRED,
    CANCELLED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case CREATED -> next == SUCCESS || next == FAILED || next == EXPIRED || next == CANCELLED;
            case SUCCESS, FAILED, EXPIRED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this != CREATED;
    }
}
