package com.example.app.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Makes non-idempotent operations (POST) safe to retry, following the IETF
 * {@code Idempotency-Key} HTTP header draft: a repeated key with the same payload returns the
 * original result; the same key with a different payload is rejected; a concurrent duplicate waits
 * briefly for the first one and otherwise gets a conflict. Bounded in size and time.
 *
 * <p>One store per resource type, so a replayed key can only ever return a {@code T}.
 */
public final class IdempotencyStore<T> {

    private static final Duration WAIT_FOR_IN_FLIGHT = Duration.ofSeconds(5);

    private record Entry<T>(String fingerprint, CompletableFuture<T> result, Instant expiresAt) {}

    private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;

    public IdempotencyStore(Clock clock, Duration ttl, int maxEntries) {
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    public T execute(String key, String fingerprint, Supplier<T> action) {
        while (true) {
            Instant now = clock.instant();
            Entry<T> mine = new Entry<>(fingerprint, new CompletableFuture<>(), now.plus(ttl));
            Entry<T> existing = entries.putIfAbsent(key, mine);

            if (existing == null) {
                return runFirst(key, mine, now, action);
            }
            if (existing.expiresAt().isBefore(now)) {
                entries.remove(key, existing);
                continue;
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException("Idempotency-Key was already used with a different request");
            }
            try {
                return existing.result().get(WAIT_FOR_IN_FLIGHT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                continue; // the first attempt failed and was forgotten; execute it ourselves
            } catch (TimeoutException e) {
                throw new ConflictException("A request with this Idempotency-Key is still being processed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("Interrupted while waiting for a concurrent identical request");
            }
        }
    }

    private T runFirst(String key, Entry<T> mine, Instant now, Supplier<T> action) {
        try {
            if (entries.size() > maxEntries) {
                entries.values().removeIf(e -> e.expiresAt().isBefore(now) && e.result().isDone());
                if (entries.size() > maxEntries) {
                    throw new CapacityExceededException("Too many outstanding idempotency keys");
                }
            }
            T result = action.get();
            mine.result().complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            entries.remove(key, mine);
            mine.result().completeExceptionally(e);
            throw e;
        }
    }
}
