package com.qrpay.outbox;

import com.qrpay.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    private OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String topic,
                        String partitionKey, String payload, Instant now) {
        super(now);
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
    }

    static OutboxEvent pending(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String topic,
                               String partitionKey, String payload) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic, partitionKey, payload, Instant.now());
    }

    void markPublished(Instant now) {
        status = OutboxStatus.PUBLISHED;
        publishedAt = now;
        attempts++;
        lastError = null;
    }

    void recordFailure(String error) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
}
