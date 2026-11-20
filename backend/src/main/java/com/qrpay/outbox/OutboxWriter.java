package com.qrpay.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event into the outbox table as part of the caller's transaction. Either the business change
 * AND the event are committed, or neither is - which is exactly what "publish to Kafka after commit"
 * cannot guarantee (the process can die between the commit and the send).
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String topic,
                       String partitionKey, Object payload) {
        try {
            outbox.save(OutboxEvent.pending(eventId, aggregateType, aggregateId, eventType, topic, partitionKey,
                    objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox payload", e);
        }
    }
}
