package com.healthrecon.rag.service.guardrails;

import com.healthrecon.rag.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window chat rate limiter keyed by user id.
 *
 * <p>Single-instance only: the window state lives in the JVM heap. With multiple
 * backend replicas a shared (e.g. Redis) store would be needed instead.
 */
@Component
public class ChatRateLimiter {

    private final Map<UUID, Deque<Long>> windows = new ConcurrentHashMap<>();

    public synchronized void check(UUID userId, int maxRequests, long windowSeconds) {
        if (maxRequests <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowStart = now - Math.max(1, windowSeconds) * 1000L;
        Deque<Long> timestamps = windows.computeIfAbsent(userId, key -> new ArrayDeque<>());
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.removeFirst();
        }
        if (timestamps.size() >= maxRequests) {
            throw new TooManyRequestsException("Too many messages. Please wait a moment and try again.");
        }
        timestamps.addLast(now);
    }
}