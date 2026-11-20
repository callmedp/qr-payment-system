package com.qrpay.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cross-instance fan-out for real-time pushes.
 *
 * <p>Problem: the merchant's browser holds an SSE connection to instance A, but the Kafka partition
 * carrying that merchant's event may be consumed by instance B. Solution: B publishes to the Redis channel
 * {@code rt:user:{userId}}; every instance is pattern-subscribed to {@code rt:user:*}; whichever instance
 * holds the connection(s) writes to them. Redis Pub/Sub is fire-and-forget, which is fine here - Kafka is
 * the durable record, the push is a best-effort UX signal (the UI also polls as a fallback).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeBroadcaster implements MessageListener {

    static final String CHANNEL_PREFIX = "rt:user:";
    static final String CHANNEL_PATTERN = CHANNEL_PREFIX + "*";

    private final StringRedisTemplate redis;
    private final SseHub hub;
    private final ObjectMapper objectMapper;

    public void publish(UUID userId, String eventName, String eventId, Object data) {
        try {
            String envelope = objectMapper.writeValueAsString(
                    new Envelope(eventName, eventId, objectMapper.writeValueAsString(data)));
            redis.convertAndSend(CHANNEL_PREFIX + userId, envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            UUID userId = UUID.fromString(channel.substring(CHANNEL_PREFIX.length()));
            Envelope envelope = objectMapper.readValue(message.getBody(), Envelope.class);
            hub.sendLocal(userId, envelope.event(), envelope.id(), envelope.data());
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Ignoring malformed realtime message: {}", e.getMessage());
        }
    }

    record Envelope(String event, String id, String data) {}
}
