package com.qrpay.merchant;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class MerchantDtos {

    private MerchantDtos() {
    }

    /** Deliberately carries no signing secret - that is fetched on demand from the webhook-secret endpoint. */
    public record MerchantView(UUID id, String businessName, String vpa, String categoryCode, MerchantStatus status,
                               String webhookUrl, Instant createdAt) {

        static MerchantView of(Merchant m) {
            return new MerchantView(m.getId(), m.getBusinessName(), m.getVpa(), m.getCategoryCode(), m.getStatus(),
                    m.getWebhookUrl(), m.getCreatedAt());
        }
    }

    /**
     * The signing secret, returned only when explicitly asked for or just rotated.
     * {@code previousSecretExpiresAt} is null unless a rotation grace window is currently open.
     */
    public record WebhookSecretView(String webhookSecret, Instant previousSecretExpiresAt) {

        static WebhookSecretView of(Merchant m, Instant now) {
            return new WebhookSecretView(m.getWebhookSecret(), m.openGraceWindowEnd(now));
        }
    }

    /** {@code url = null} removes the webhook. */
    public record UpdateWebhookRequest(
            @Size(max = 500) @Pattern(regexp = "^https?://.+", message = "must be an http(s) URL") String url) {}
}
