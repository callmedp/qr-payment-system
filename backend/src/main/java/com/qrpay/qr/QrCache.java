package com.qrpay.qr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside for {@link QrSnapshot}. Dynamic codes are cached until they expire (so the cache entry
 * disappears exactly when the code stops being payable); static codes for an hour. Every status
 * change evicts the key after commit. Redis failures degrade to a DB read, never to an error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class QrCache {

    private static final Duration STATIC_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    Optional<QrSnapshot> get(UUID qrId) {
        try {
            String json = redis.opsForValue().get(key(qrId));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, QrSnapshot.class));
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("QR cache read failed for {}: {}", qrId, e.getMessage());
            return Optional.empty();
        }
    }

    void put(QrSnapshot snapshot) {
        if (snapshot.status() != QrStatus.ACTIVE) {
            return;
        }
        Duration ttl = snapshot.expiresAt() == null
                ? STATIC_TTL
                : Duration.between(Instant.now(), snapshot.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(key(snapshot.qrId()), objectMapper.writeValueAsString(snapshot), ttl);
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("QR cache write failed for {}: {}", snapshot.qrId(), e.getMessage());
        }
    }

    void evict(UUID qrId) {
        try {
            redis.delete(key(qrId));
        } catch (DataAccessException e) {
            log.warn("QR cache evict failed for {}", qrId);
        }
    }

    private static String key(UUID qrId) {
        return "qr:" + qrId;
    }
}
