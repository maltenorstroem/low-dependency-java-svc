package com.example.app.security;

import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertTrue;

import com.example.app.observability.Metrics;
import com.example.app.testing.Test;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public class JwksClientTest implements AutoCloseable {

    /** A clock the test moves by hand, so cache expiry needs no sleeping. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-18T12:00:00Z"));

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final StubJwksServer idp;
    private final TestClock clock = new TestClock();

    public JwksClientTest() throws Exception {
        idp = new StubJwksServer();
    }

    @Override
    public void close() {
        idp.close();
    }

    @Test
    void fetchesOnceAndServesFromCacheUntilTheTtlExpires() throws Exception {
        idp.serve(TokenFixtures.jwks("key-1", TokenFixtures.rsa()));
        try (JwksClient keys = client()) {
            for (int i = 0; i < 20; i++) {
                assertTrue(keys.find("key-1").isPresent(), "key is found");
            }
            assertEquals(1, idp.requestCount());

            clock.advance(Duration.ofSeconds(301));
            assertTrue(keys.find("key-1").isPresent(), "key is still found after the ttl");
            assertEquals(2, idp.requestCount());
        }
    }

    @Test
    void picksUpARotatedKeyWithoutARestart() throws Exception {
        idp.serve(TokenFixtures.jwks("old", TokenFixtures.rsa()));
        try (JwksClient keys = client()) {
            assertTrue(keys.find("old").isPresent(), "the original key");

            idp.serve(TokenFixtures.jwks("new", TokenFixtures.rsa()));
            clock.advance(Duration.ofSeconds(31)); // past minRefresh
            assertTrue(keys.find("new").isPresent(), "the rotated key, fetched on the unknown kid");
        }
    }

    /**
     * Without the rate limit, anyone could point this service at its own identity provider simply
     * by sending tokens with invented key ids.
     */
    @Test
    void rateLimitsRefreshesTriggeredByUnknownKeyIds() throws Exception {
        idp.serve(TokenFixtures.jwks("key-1", TokenFixtures.rsa()));
        try (JwksClient keys = client()) {
            keys.find("key-1");
            int afterWarmUp = idp.requestCount();

            for (int i = 0; i < 500; i++) {
                assertTrue(keys.find("invented-" + i).isEmpty(), "an invented kid resolves to nothing");
            }

            assertEquals(afterWarmUp, idp.requestCount());
        }
    }

    @Test
    void keepsServingTheLastGoodKeysWhileTheProviderIsDown() throws Exception {
        idp.serve(TokenFixtures.jwks("key-1", TokenFixtures.rsa()));
        try (JwksClient keys = client()) {
            assertTrue(keys.find("key-1").isPresent(), "fetched while healthy");

            idp.failWith(503);
            clock.advance(Duration.ofSeconds(301)); // ttl expired, refresh will fail
            assertTrue(keys.find("key-1").isPresent(), "the cached key still verifies tokens");
        }
    }

    @Test
    void failsClosedOnceTheCachedKeysArePastMaxStale() throws Exception {
        idp.serve(TokenFixtures.jwks("key-1", TokenFixtures.rsa()));
        try (JwksClient keys = client()) {
            assertTrue(keys.find("key-1").isPresent(), "fetched while healthy");

            idp.failWith(500);
            clock.advance(Duration.ofSeconds(3_601));

            assertTrue(keys.find("key-1").isEmpty(), "stale keys are dropped rather than trusted");
            assertEquals(0, keys.size());
        }
    }

    @Test
    void rejectsAnOversizedDocument() throws Exception {
        idp.serve("{\"keys\":[],\"padding\":\"" + "x".repeat(4_096) + "\"}");
        try (JwksClient keys = new JwksClient(idp.url(), Duration.ofSeconds(300), Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofSeconds(3_600), 1_024, clock, new Metrics(clock))) {
            assertTrue(keys.find("key-1").isEmpty(), "an oversized key set yields no keys");
            assertEquals(0, keys.size());
        }
    }

    @Test
    void survivesAMalformedOrEmptyDocument() throws Exception {
        idp.serve("not json at all");
        try (JwksClient keys = client()) {
            assertTrue(keys.find("key-1").isEmpty(), "garbage yields no keys");

            idp.serve("{\"nothing\":true}");
            clock.advance(Duration.ofSeconds(31));
            assertTrue(keys.find("key-1").isEmpty(), "a document without a keys array yields no keys");
        }
    }

    @Test
    void reportsFetchOutcomesAsMetrics() throws Exception {
        Metrics metrics = new Metrics(clock);
        idp.serve(TokenFixtures.jwks("key-1", TokenFixtures.rsa()));
        try (JwksClient keys = new JwksClient(idp.url(), Duration.ofSeconds(300), Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofSeconds(3_600), 131_072, clock, metrics)) {
            keys.find("key-1");
            idp.failWith(500);
            clock.advance(Duration.ofSeconds(301));
            keys.find("key-1");
        }
        String rendered = new String(metrics.render(), java.nio.charset.StandardCharsets.UTF_8);
        com.example.app.testing.Assert.assertContains(rendered, "auth_jwks_fetches_total{outcome=\"success\"} 1");
        com.example.app.testing.Assert.assertContains(rendered, "auth_jwks_fetches_total{outcome=\"failure\"} 1");
    }

    private JwksClient client() {
        return new JwksClient(idp.url(), Duration.ofSeconds(300), Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofSeconds(3_600), 131_072, clock, new Metrics(clock));
    }
}
