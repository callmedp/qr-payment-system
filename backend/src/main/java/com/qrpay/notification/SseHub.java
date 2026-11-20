package com.qrpay.notification;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry of Server-Sent-Event connections held by THIS instance. A user may have several tabs/devices
 * open, so each user maps to a set of emitters.
 */
@Slf4j
@Component
public class SseHub {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("at", Instant.now().toString())));
        } catch (IOException e) {
            remove(userId, emitter);
        }
        return emitter;
    }

    public void sendLocal(UUID userId, String eventName, String eventId, String jsonData) {
        Set<SseEmitter> targets = emitters.get(userId);
        if (targets == null) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().id(eventId).name(eventName).data(jsonData, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                remove(userId, emitter);
            }
        }
    }

    /** Comment frames keep idle connections alive through proxies/LBs and flush out dead clients. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        emitters.forEach((userId, set) -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException e) {
                remove(userId, emitter);
            }
        }));
    }

    public int connectionCount() {
        return emitters.values().stream().mapToInt(Set::size).sum();
    }

    private void remove(UUID userId, SseEmitter emitter) {
        emitters.computeIfPresent(userId, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
