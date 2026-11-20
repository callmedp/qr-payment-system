package com.qrpay.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view over the {@code qrpay.*} section of application.properties. */
@ConfigurationProperties(prefix = "qrpay")
public record QrPayProperties(
        Security security,
        Qr qr,
        Payment payment,
        Idempotency idempotency,
        RateLimit rateLimit,
        Kafka kafka,
        Outbox outbox,
        Jobs jobs,
        Webhook webhook,
        Demo demo,
        Cors cors) {

    public record Security(String jwtSecret, Duration jwtTtl, int pinMaxAttempts, Duration pinLockout) {}

    public record Qr(String signingSecret, String scheme, Duration defaultExpiry, Duration maxExpiry) {}

    public record Payment(Duration pendingTtl, Duration lockTtl, long maxAmount, String currency) {}

    public record Idempotency(Duration ttl) {}

    public record RateLimit(Duration window, Map<String, Integer> buckets) {}

    public record Kafka(String paymentEventsTopic, int partitions, short replicationFactor) {}

    public record Outbox(long pollIntervalMs, int batchSize, Duration retention) {}

    public record Jobs(long expiryIntervalMs, long reconciliationIntervalMs) {}

    /** {@code secretGracePeriod}: how long a rotated-out signing secret keeps verifying. */
    public record Webhook(Duration connectTimeout, Duration readTimeout, Duration secretGracePeriod,
                          boolean devSinkEnabled) {}

    public record Demo(long signupBonus) {}

    public record Cors(List<String> allowedOrigins) {}
}
