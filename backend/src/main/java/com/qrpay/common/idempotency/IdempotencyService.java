package com.qrpay.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.config.QrPayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Stripe-style idempotency keys backed by Redis.
 *
 * <pre>
 *   first call   : SET idem:{scope}:{user}:{key} {IN_PROGRESS,hash} NX EX ttl -> run action -> store {COMPLETED,hash,response}
 *   retry (done) : same hash -> replay stored response (no side effects re-executed)
 *   retry (busy) : 409 REQUEST_IN_PROGRESS
 *   key reuse    : different request hash -> 422 IDEMPOTENCY_KEY_REUSED
 * </pre>
 *
 * Failed actions release the key so the client can retry. If Redis is down we still execute:
 * the payments table has a UNIQUE(payer_user_id, idempotency_key) constraint and the payment state
 * machine makes confirm naturally idempotent, so the database remains the durable backstop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final QrPayProperties props;

    public <T> Result<T> execute(String scope, UUID principal, String idempotencyKey, Object request,
                                 Class<T> responseType, Supplier<T> action) {
        validateKey(idempotencyKey);
        String redisKey = "idem:" + scope + ":" + principal + ":" + idempotencyKey;
        String requestHash = hash(request);

        Boolean claimed;
        try {
            claimed = redis.opsForValue().setIfAbsent(redisKey, write(new Entry(IN_PROGRESS, requestHash, null)),
                    props.idempotency().ttl());
        } catch (DataAccessException e) {
            log.warn("Idempotency store unavailable, relying on database guards for key {}", idempotencyKey);
            return new Result<>(action.get(), false);
        }

        if (Boolean.TRUE.equals(claimed)) {
            try {
                T response = action.get();
                store(redisKey, new Entry(COMPLETED, requestHash, write(response)));
                return new Result<>(response, false);
            } catch (RuntimeException e) {
                safeDelete(redisKey);
                throw e;
            }
        }

        Entry existing = read(redisKey);
        if (existing == null) {
            // expired between SETNX and GET: extremely unlikely, just run again under DB guards
            return new Result<>(action.get(), false);
        }
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (IN_PROGRESS.equals(existing.state())) {
            throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        return new Result<>(readValue(existing.response(), responseType), true);
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header must be 1-64 characters");
        }
    }

    private void store(String redisKey, Entry entry) {
        try {
            redis.opsForValue().set(redisKey, write(entry), props.idempotency().ttl());
        } catch (DataAccessException e) {
            log.warn("Could not persist idempotent response for {}", redisKey);
        }
    }

    private Entry read(String redisKey) {
        try {
            String raw = redis.opsForValue().get(redisKey);
            return raw == null ? null : objectMapper.readValue(raw, Entry.class);
        } catch (DataAccessException | JsonProcessingException e) {
            return null;
        }
    }

    private void safeDelete(String redisKey) {
        try {
            redis.delete(redisKey);
        } catch (DataAccessException e) {
            log.warn("Could not release idempotency key {}", redisKey);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(write(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Entry(String state, String requestHash, String response) {}

    /** @param replayed true when the response came from the idempotency store rather than a fresh execution */
    public record Result<T>(T body, boolean replayed) {}
}
