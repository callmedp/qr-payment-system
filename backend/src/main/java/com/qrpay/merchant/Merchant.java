package com.qrpay.merchant;

import com.qrpay.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    /** Virtual payment address - the human-readable payee handle shown to customers. */
    @Column(nullable = false, updatable = false)
    private String vpa;

    @Column(name = "category_code", nullable = false)
    private String categoryCode;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "webhook_secret", nullable = false)
    private String webhookSecret;

    /** Kept valid for a grace window after a rotation so in-flight deliveries still verify. */
    @Column(name = "previous_webhook_secret")
    private String previousWebhookSecret;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantStatus status;

    private Merchant(UUID userId, String businessName, String vpa, String categoryCode, String webhookSecret, Instant now) {
        super(now);
        this.userId = userId;
        this.businessName = businessName;
        this.vpa = vpa;
        this.categoryCode = categoryCode;
        this.webhookSecret = webhookSecret;
        this.status = MerchantStatus.ACTIVE;
    }

    public static Merchant onboard(UUID userId, String businessName, String vpa, String categoryCode, String webhookSecret) {
        return new Merchant(userId, businessName, vpa, categoryCode, webhookSecret, Instant.now());
    }

    public void updateWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /**
     * Issues a new signing secret and keeps the old one usable until {@code now + grace}, so a merchant
     * can update their receiver without dropping the webhooks delivered in between.
     */
    public void rotateWebhookSecret(String newSecret, Duration grace, Instant now) {
        this.previousWebhookSecret = this.webhookSecret;
        this.previousSecretExpiresAt = now.plus(grace);
        this.webhookSecret = newSecret;
    }

    /**
     * When the previous secret stops verifying, or {@code null} if no grace window is open (never
     * rotated, or the window has already elapsed). The single place the grace rule is interpreted.
     */
    public Instant openGraceWindowEnd(Instant now) {
        return previousWebhookSecret != null
                && previousSecretExpiresAt != null
                && now.isBefore(previousSecretExpiresAt)
                ? previousSecretExpiresAt
                : null;
    }

    /** Secrets a delivery must be signed with: the current one, plus the previous one while in grace. */
    public List<String> activeWebhookSecrets(Instant now) {
        return openGraceWindowEnd(now) != null
                ? List.of(webhookSecret, previousWebhookSecret)
                : List.of(webhookSecret);
    }

    public boolean isActive() {
        return status == MerchantStatus.ACTIVE;
    }
}
