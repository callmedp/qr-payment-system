package com.qrpay.merchant;

import com.qrpay.auth.UserAccount;
import com.qrpay.common.error.ApiException;
import com.qrpay.config.QrPayProperties;
import com.qrpay.merchant.MerchantDtos.MerchantView;
import com.qrpay.merchant.MerchantDtos.WebhookSecretView;
import com.qrpay.security.AuthUser;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String VPA_DOMAIN = "@qrpay";

    private final MerchantRepository merchants;
    private final QrPayProperties props;

    @Transactional
    public Merchant onboard(UserAccount user, String businessName, String categoryCode) {
        return merchants.save(Merchant.onboard(user.getId(), businessName.trim(), generateVpa(businessName),
                categoryCode, generateSecret()));
    }

    @Transactional(readOnly = true)
    public Merchant getByUserId(UUID userId) {
        return merchants.findByUserId(userId).orElseThrow(() -> ApiException.notFound("Merchant"));
    }

    @Transactional(readOnly = true)
    public Merchant getById(UUID merchantId) {
        return merchants.findById(merchantId).orElseThrow(() -> ApiException.notFound("Merchant"));
    }

    @Transactional(readOnly = true)
    public MerchantView me(AuthUser principal) {
        return MerchantView.of(getById(principal.merchantId()));
    }

    @Transactional
    public MerchantView updateWebhook(AuthUser principal, String url) {
        Merchant merchant = getById(principal.merchantId());
        merchant.updateWebhookUrl(url == null || url.isBlank() ? null : url.trim());
        return MerchantView.of(merchant);
    }

    @Transactional(readOnly = true)
    public WebhookSecretView revealWebhookSecret(AuthUser principal) {
        return WebhookSecretView.of(getById(principal.merchantId()), Instant.now());
    }

    /**
     * Issues a fresh signing secret. The old one keeps verifying for
     * {@code qrpay.webhook.secret-grace-period}, during which deliveries are signed with both, so the
     * merchant can update their receiver without losing any webhook.
     */
    @Transactional
    public WebhookSecretView rotateWebhookSecret(AuthUser principal) {
        Merchant merchant = getById(principal.merchantId());
        Instant now = Instant.now();
        merchant.rotateWebhookSecret(generateSecret(), props.webhook().secretGracePeriod(), now);
        log.info("Webhook secret rotated for merchant {}, previous secret valid until {}",
                merchant.getId(), merchant.openGraceWindowEnd(now));
        return WebhookSecretView.of(merchant, now);
    }

    private static String generateSecret() {
        return "whsec_" + HexFormat.of().formatHex(randomBytes(24));
    }

    /** "Chai Point & Co." -> "chai-point-co-3f9a@qrpay" */
    private String generateVpa(String businessName) {
        String slug = businessName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "merchant";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            String vpa = slug + "-" + HexFormat.of().formatHex(randomBytes(2)) + VPA_DOMAIN;
            if (!merchants.existsByVpa(vpa)) {
                return vpa;
            }
        }
        return slug + "-" + HexFormat.of().formatHex(randomBytes(6)) + VPA_DOMAIN;
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
