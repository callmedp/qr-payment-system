package com.qrpay.webhook;

import com.qrpay.merchant.MerchantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local stand-in for a merchant's server so the webhook path can be exercised end to end. Set a merchant's
 * webhook URL to {@code http://localhost:8080/api/v1/dev/webhook-sink}. It verifies the signature exactly
 * like a merchant integration would. Disabled with {@code qrpay.webhook.dev-sink-enabled=false}.
 */
@Tag(name = "Dev tools")
@RestController
@RequestMapping("/api/v1/dev/webhook-sink")
@ConditionalOnProperty(name = "qrpay.webhook.dev-sink-enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevWebhookSinkController {

    private static final int CAPACITY = 50;

    private final MerchantRepository merchants;
    private final Deque<Received> received = new ConcurrentLinkedDeque<>();

    @Operation(summary = "Receive a webhook (acts like a merchant server)")
    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-QrPay-Signature") String signature,
                                        @RequestHeader("X-QrPay-Event-Id") String eventId,
                                        @RequestHeader("X-QrPay-Event-Type") String eventType,
                                        @RequestHeader("X-QrPay-Merchant-Id") UUID merchantId,
                                        @RequestBody String body) {
        boolean valid = merchants.findById(merchantId)
                .map(m -> WebhookSigner.verify(m.getWebhookSecret(), signature, body, Duration.ofMinutes(5), Instant.now()))
                .orElse(false);
        received.addFirst(new Received(Instant.now(), eventId, eventType, merchantId, valid, body));
        while (received.size() > CAPACITY) {
            received.pollLast();
        }
        return valid ? ResponseEntity.ok().build() : ResponseEntity.status(401).build();
    }

    @Operation(summary = "Webhooks received by the dev sink, newest first")
    @GetMapping
    public List<Received> list() {
        return List.copyOf(received);
    }

    public record Received(Instant receivedAt, String eventId, String eventType, UUID merchantId,
                           boolean signatureValid, String body) {}
}
