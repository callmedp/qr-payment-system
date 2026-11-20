package com.qrpay.merchant;

import com.qrpay.common.ratelimit.RateLimited;
import com.qrpay.merchant.MerchantDtos.MerchantView;
import com.qrpay.merchant.MerchantDtos.UpdateWebhookRequest;
import com.qrpay.merchant.MerchantDtos.WebhookSecretView;
import com.qrpay.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Merchants")
@RestController
@RequestMapping("/api/v1/merchants")
@PreAuthorize("hasRole('MERCHANT')")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @Operation(summary = "Merchant profile, VPA and webhook settings")
    @GetMapping("/me")
    public MerchantView me(@AuthenticationPrincipal AuthUser user) {
        return merchantService.me(user);
    }

    @Operation(summary = "Set or clear the webhook URL that receives signed payment events")
    @PutMapping("/me/webhook")
    public MerchantView updateWebhook(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateWebhookRequest request) {
        return merchantService.updateWebhook(user, request.url());
    }

    @Operation(summary = "Reveal the current webhook signing secret")
    @GetMapping("/me/webhook/secret")
    public WebhookSecretView webhookSecret(@AuthenticationPrincipal AuthUser user) {
        return merchantService.revealWebhookSecret(user);
    }

    @Operation(summary = "Rotate the webhook signing secret; the previous one keeps verifying "
            + "for the grace period, during which deliveries are signed with both")
    @PostMapping("/me/webhook/secret")
    @RateLimited("auth")
    public WebhookSecretView rotateWebhookSecret(@AuthenticationPrincipal AuthUser user) {
        return merchantService.rotateWebhookSecret(user);
    }
}
