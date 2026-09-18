package com.example.app.security;

import com.example.app.observability.Log;
import com.example.app.observability.Metrics;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The identity provider's public signing keys, fetched over HTTPS and cached.
 *
 * <p>Three behaviours matter more than the fetching itself:
 *
 * <ul>
 *   <li><b>It refreshes on an unknown {@code kid}</b>, so a key rotation is picked up without a
 *       restart — but at most once per {@code minRefresh}. Without that limit, a flood of tokens
 *       bearing invented key ids would turn this service into an amplifier pointed at the identity
 *       provider.
 *   <li><b>It tolerates an outage</b>: the last good key set keeps serving while the provider is
 *       unreachable, up to {@code maxStale}.
 *   <li><b>It fails closed.</b> Past {@code maxStale} the keys are dropped and every token is
 *       rejected. There is no path in this class that accepts a token it could not verify.
 * </ul>
 */
public final class JwksClient implements JwkSource, AutoCloseable {

    private static final Log LOG = Log.get(JwksClient.class);

    private record Snapshot(Map<String, VerificationKey> keys, Instant fetchedAt, Instant lastAttempt) {}

    private final URI url;
    private final Duration ttl;
    private final Duration minRefresh;
    private final Duration maxStale;
    private final Duration timeout;
    private final int maxBytes;
    private final Clock clock;
    private final Metrics metrics;
    private final HttpClient http;
    private final ReentrantLock refreshing = new ReentrantLock();
    private final AtomicReference<Snapshot> snapshot;

    public JwksClient(URI url, Duration ttl, Duration minRefresh, Duration timeout, Duration maxStale,
            int maxBytes, Clock clock, Metrics metrics) {
        this.url = url;
        this.ttl = ttl;
        this.minRefresh = minRefresh;
        this.maxStale = maxStale;
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.clock = clock;
        this.metrics = metrics;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // A redirect on a key endpoint is an SSRF pivot, and a legitimate provider does
                // not need one.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.snapshot = new AtomicReference<>(new Snapshot(Map.of(), Instant.EPOCH, Instant.EPOCH));
    }

    @Override
    public Optional<VerificationKey> find(String kid) {
        Snapshot current = usable(snapshot.get());
        if (!needsRefresh(current, kid)) {
            return lookup(current, kid);
        }
        // Holding no keys at all is a cold start or an outage. Such a caller waits for whatever
        // fetch is already in flight instead of giving up, because rejecting a token we are one
        // moment away from being able to verify would 503 the whole first burst after a restart.
        // A caller that does have keys never blocks: it serves them and moves on.
        boolean cold = current.keys().isEmpty();
        if (rateLimited(current)) {
            if (!cold) {
                return lookup(current, kid);
            }
            awaitRefresh();
        } else {
            refresh(cold);
        }
        return lookup(usable(snapshot.get()), kid);
    }

    private boolean rateLimited(Snapshot current) {
        return clock.instant().isBefore(current.lastAttempt().plus(minRefresh));
    }

    @Override
    public int size() {
        return usable(snapshot.get()).keys().size();
    }

    /** A best-effort warm-up so the first real request does not pay for the first fetch. */
    public void warmUp() {
        refresh(false);
    }

    /** Blocks only until whoever holds the lock is done; never starts a fetch of its own. */
    private void awaitRefresh() {
        refreshing.lock();
        refreshing.unlock();
    }

    @Override
    public void close() {
        http.close();
    }

    /** Drops a key set that has gone past {@code maxStale}: better no keys than stale ones. */
    private Snapshot usable(Snapshot current) {
        if (current.keys().isEmpty() || clock.instant().isBefore(current.fetchedAt().plus(maxStale))) {
            return current;
        }
        Snapshot dropped = new Snapshot(Map.of(), current.fetchedAt(), current.lastAttempt());
        snapshot.compareAndSet(current, dropped);
        LOG.warn("auth.jwks_expired", "staleSeconds", Duration.between(current.fetchedAt(), clock.instant()).toSeconds());
        return dropped;
    }

    private boolean needsRefresh(Snapshot current, String kid) {
        return lookup(current, kid).isEmpty() || !clock.instant().isBefore(current.fetchedAt().plus(ttl));
    }

    /** A token without a {@code kid} is only resolvable when the provider publishes a single key. */
    private static Optional<VerificationKey> lookup(Snapshot current, String kid) {
        if (kid != null) {
            return Optional.ofNullable(current.keys().get(kid));
        }
        return current.keys().size() == 1 ? current.keys().values().stream().findFirst() : Optional.empty();
    }

    /**
     * Single-flight. A thread that loses the race gives up and serves the current snapshot,
     * unless {@code join}, in which case it waits for the winner's result. Locking is explicit
     * rather than {@code synchronized} so nothing here can pin a virtual thread to its carrier.
     */
    private void refresh(boolean join) {
        if (join) {
            refreshing.lock();
        } else if (!refreshing.tryLock()) {
            return;
        }
        try {
            Snapshot current = snapshot.get();
            if (rateLimited(current)) {
                // Another thread fetched while we waited for the lock: use its result.
                return;
            }
            long start = System.nanoTime();
            Map<String, VerificationKey> keys = Jwks.parse(fetch());
            snapshot.set(new Snapshot(keys, clock.instant(), clock.instant()));
            metrics.jwksFetch("success");
            metrics.jwksKeysHeld(keys.size());
            LOG.info("auth.jwks_refreshed", "keys", keys.size(), "durationMs", (System.nanoTime() - start) / 1_000_000.0);
        } catch (IOException | IllegalArgumentException e) {
            // Record the attempt even on failure: that is what rate-limits the retries.
            Snapshot current = snapshot.get();
            snapshot.set(new Snapshot(current.keys(), current.fetchedAt(), clock.instant()));
            metrics.jwksFetch("failure");
            LOG.warn("auth.jwks_fetch_failed", "url", url.toString(), "reason", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.jwksFetch("failure");
        } finally {
            refreshing.unlock();
        }
    }

    private String fetch() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("JWKS endpoint returned " + response.statusCode());
            }
            // Bounded exactly like a request body: read one byte past the limit and reject,
            // rather than buffering whatever the other end decides to send.
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("JWKS document exceeds " + maxBytes + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
