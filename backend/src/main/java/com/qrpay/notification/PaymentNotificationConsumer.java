package com.qrpay.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrpay.events.PaymentEvent;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumer group "notification-service": turns payment events into real-time pushes for both parties. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentNotificationConsumer {

    private final RealtimeBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${qrpay.kafka.payment-events-topic}", groupId = "notification-service")
    public void onPaymentEvent(String json) throws JsonProcessingException {
        PaymentEvent event = objectMapper.readValue(json, PaymentEvent.class);
        PaymentNotification notification = new PaymentNotification(event.eventId(), event.eventType(),
                event.paymentId(), event.status(), event.failureReason(), event.qrId(), event.orderRef(),
                event.amount(), event.currency(), event.merchantName(), event.payerName(), event.occurredAt());
        String id = event.eventId().toString();
        broadcaster.publish(event.merchantUserId(), "payment", id, notification);
        broadcaster.publish(event.payerUserId(), "payment", id, notification);
        log.debug("Pushed {} for payment {}", event.eventType(), event.paymentId());
    }

    /** What the browser receives. The UI de-duplicates on eventId (delivery is at-least-once). */
    public record PaymentNotification(UUID eventId, String eventType, UUID paymentId, String status,
                                      String failureReason, UUID qrId, String orderRef, long amount, String currency,
                                      String merchantName, String payerName, Instant occurredAt) {}
}
