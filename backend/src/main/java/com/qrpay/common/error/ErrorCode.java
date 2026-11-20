package com.qrpay.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes. Clients switch on {@code code}, never on the message. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You are not allowed to perform this action"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Resource conflict"),

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email is already registered"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),

    QR_INVALID(HttpStatus.BAD_REQUEST, "QR code is invalid or has been tampered with"),
    QR_EXPIRED(HttpStatus.GONE, "QR code has expired"),
    QR_ALREADY_PAID(HttpStatus.CONFLICT, "QR code has already been paid"),
    QR_INACTIVE(HttpStatus.CONFLICT, "QR code is not active"),
    INVALID_EXPIRY(HttpStatus.BAD_REQUEST, "Requested QR expiry is outside the allowed range"),
    DUPLICATE_ORDER_REF(HttpStatus.CONFLICT, "A QR code already exists for this order reference"),

    AMOUNT_REQUIRED(HttpStatus.BAD_REQUEST, "Amount is required when paying a static QR code"),
    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "Amount does not match the amount encoded in the QR code"),
    AMOUNT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "Amount exceeds the per-transaction limit"),
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient wallet balance"),
    INVALID_PIN(HttpStatus.UNPROCESSABLE_ENTITY, "Incorrect PIN"),
    PIN_LOCKED(HttpStatus.LOCKED, "Too many incorrect PIN attempts, try again later"),
    PAYMENT_INVALID_STATE(HttpStatus.CONFLICT, "Payment cannot be processed in its current state"),
    PAYMENT_EXPIRED(HttpStatus.GONE, "Payment session has expired"),

    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key was already used with a different request body"),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "A request with this Idempotency-Key is still being processed"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Another payment for this QR code is in progress, retry shortly"),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, slow down"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
