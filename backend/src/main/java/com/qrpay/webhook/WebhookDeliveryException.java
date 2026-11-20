package com.qrpay.webhook;

/** Retryable delivery failure (timeout, connection refused, 5xx, 408, 429). */
public class WebhookDeliveryException extends RuntimeException {

    public WebhookDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
