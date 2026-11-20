package com.qrpay.common.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis lease lock ({@code SET key token NX PX ttl}; release = compare-and-delete in Lua).
 *
 * <p>This lock is an <b>optimisation</b>, not the correctness guarantee: it stops two customers
 * from racing on the same dynamic QR before they ever reach Postgres, and keeps the scheduled jobs
 * to one node at a time. Money-safety comes from database row locks, conditional updates and unique
 * constraints. That is why the lock fails <em>open</em> when Redis is unreachable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLock {

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public Optional<Lease> tryAcquire(String name, Duration ttl) {
        String key = "lock:" + name;
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? Optional.of(new Lease(key, token)) : Optional.empty();
        } catch (DataAccessException e) {
            log.warn("Redis unavailable while acquiring lock {}, continuing without it (DB guards still apply)", key);
            return Optional.of(new Lease(key, null));
        }
    }

    /** A held lock. Closing releases it only if we still own it (the TTL may have expired). */
    public final class Lease implements AutoCloseable {
        private final String key;
        private final String token;

        private Lease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            if (token == null) {
                return;
            }
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (DataAccessException e) {
                log.warn("Failed to release lock {}, it will expire by TTL", key);
            }
        }
    }
}
