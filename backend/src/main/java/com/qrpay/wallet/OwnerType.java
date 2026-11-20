package com.qrpay.wallet;

public enum OwnerType {
    USER,
    MERCHANT,
    /** Represents money entering/leaving the closed loop (e.g. the bank settlement account). */
    SYSTEM
}
