package com.qrpay.common.ratelimit;

import com.qrpay.config.QrPayProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter in Redis, shared by every app instance. INCR + PEXPIRE run atomically in a
 * Lua script so a crash between the two can never leave a counter without a TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private static final RedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final QrPayProperties props;

    public Decision tryConsume(String bucket, String subject) {
        Integer limit = props.rateLimit().buckets().get(bucket);
        if (limit == null) {
            return new Decision(true, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        }
        long windowMs = props.rateLimit().window().toMillis();
        long now = System.currentTimeMillis();
        long windowIndex = now / windowMs;
        long retryAfterMs = windowMs - (now % windowMs);
        String key = "rl:" + bucket + ":" + subject + ":" + windowIndex;
        try {
            Long count = redis.execute(INCREMENT, List.of(key), String.valueOf(windowMs));
            long used = count == null ? 0 : count;
            return new Decision(used <= limit, limit, Math.max(0, limit - used), retryAfterMs);
        } catch (DataAccessException e) {
            // Fail open: an outage of the limiter must not take payments down with it.
            log.warn("Rate limiter unavailable, allowing request for bucket {}", bucket);
            return new Decision(true, limit, limit, 0);
        }
    }

    public record Decision(boolean allowed, int limit, long remaining, long retryAfterMs) {}
}
