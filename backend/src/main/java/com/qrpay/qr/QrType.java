package com.qrpay.qr;

public enum QrType {
    /** Printed once, reusable forever, customer types the amount. */
    STATIC,
    /** Generated per order: fixed amount, single use, short expiry. */
    DYNAMIC
}
