package com.qrpay.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrpay.events.PaymentEvent;
import com.qrpay.merchant.Merchant;
import com.qrpay.merchant.MerchantRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Consumer group "webhook-dispatcher": delivers signed payment events to the merchant's server.
 *
 * <p><b>Non-blocking retries.</b> A failing merchant endpoint must not stall other merchants that share the
 * partition. {@code @RetryableTopic} moves a failed record to {@code payment.events-webhook-retry-N}
 * topics with growing delays (2s, 6s, 18s, 54s, 162s) and finally to {@code payment.events-webhook-dlt},
 * while the main topic keeps flowing. Anything except an unparseable payload is retried - including transient
 * infrastructure errors - so a short database or network blip never dead-letters a webhook.
 *
 * <p><b>Idempotency.</b> Kafka + retries = at-least-once, so successful deliveries are remembered in Redis
 * per eventId, and merchants are told to de-duplicate on {@code X-QrPay-Event-Id} as well.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    private static final Duration DELIVERED_MARKER_TTL = Duration.ofDays(7);

    private final MerchantRepository merchants;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RestClient webhookRestClient;

    @RetryableTopic(
            attempts = "6",
            backoff = @Backoff(delay = 2000, multiplier = 3.0, maxDelay = 300_000),
            numPartitions = "${qrpay.kafka.partitions}",
            replicationFactor = "${qrpay.kafka.replication-factor}",
            // retry every failure (merchant 5xx/timeouts AND transient infra errors such as a DB blip);
            // only a payload we cannot parse is hopeless and goes straight to the DLT
            exclude = JsonProcessingException.class,
            retryTopicSuffix = "-webhook-retry",
            dltTopicSuffix = "-webhook-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = "${qrpay.kafka.payment-events-topic}", groupId = "webhook-dispatcher")
    public void dispatch(String json) throws JsonProcessingException {
        PaymentEvent event = objectMapper.readValue(json, PaymentEvent.class);
        String deliveredKey = "webhook:delivered:" + event.eventId();
        if (alreadyDelivered(deliveredKey)) {
            return;
        }
        Merchant merchant = merchants.findById(event.merchantId()).orElse(null);
        if (merchant == null || merchant.getWebhookUrl() == null) {
            return; // merchant has not subscribed
        }

        String body = objectMapper.writeValueAsString(WebhookPayload.from(event));
        Instant now = Instant.now();
        String signature = WebhookSigner.sign(merchant.activeWebhookSecrets(now), now.getEpochSecond(), body);
        try {
            webhookRestClient.post()
                    .uri(merchant.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-QrPay-Signature", signature)
                    .header("X-QrPay-Event-Id", event.eventId().toString())
                    .header("X-QrPay-Event-Type", event.eventType())
                    .header("X-QrPay-Merchant-Id", merchant.getId().toString())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (e.getStatusCode().is5xxServerError() || status == 408 || status == 429) {
                throw new WebhookDeliveryException("Merchant endpoint returned " + status, e);
            }
            // other 4xx: the merchant rejected it; retrying the same payload will not help
            log.warn("Webhook {} rejected by merchant {} with {}", event.eventId(), merchant.getId(), status);
            return;
        } catch (ResourceAccessException e) {
            throw new WebhookDeliveryException("Merchant endpoint unreachable: " + e.getMessage(), e);
        }
        markDelivered(deliveredKey);
        log.info("Webhook {} ({}) delivered to merchant {}", event.eventId(), event.eventType(), merchant.getId());
    }

    @DltHandler
    public void onDeadLetter(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        // Production: persist to a webhook_failures table and surface in the merchant dashboard for manual replay.
        log.error("Webhook permanently failed after all retries (topic {}): {}", topic, json);
    }

    private boolean alreadyDelivered(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (DataAccessException e) {
            return false;
        }
    }

    private void markDelivered(String key) {
        try {
            redis.opsForValue().set(key, "1", DELIVERED_MARKER_TTL);
        } catch (DataAccessException e) {
            log.warn("Could not record webhook delivery marker {}", key);
        }
    }
}
