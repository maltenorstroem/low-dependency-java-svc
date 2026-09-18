package com.example.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.app.testing.HttpTestSupport;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * How the service copes with the identity provider changing or disappearing.
 *
 * <p>The zero-dependency suite drives these with an injectable clock and never sleeps. Nimbus's
 * cache takes no clock, so the TTLs here are short and real instead. The one case not covered is
 * failing closed once keys are older than {@code jwksMaxStaleSeconds}, whose floor is 60 seconds:
 * asserting it would mean a minute of wall time in every build, which is not worth it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwksBehaviourTest extends HttpTestSupport {

    private static final StubJwksServer IDP;
    private static final TokenFixtures FIRST;
    private static final TokenFixtures ROTATED;

    static {
        try {
            FIRST = new TokenFixtures();
            ROTATED = new TokenFixtures();
            IDP = new StubJwksServer();
            IDP.serve(FIRST.jwks());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.issuer", () -> TokenFixtures.ISSUER);
        registry.add("app.auth.audience", () -> TokenFixtures.AUDIENCE);
        registry.add("app.auth.jwks-url", () -> IDP.url().toString());
        registry.add("app.auth.enabled", () -> "true");
        registry.add("app.auth.jwks-ttl-seconds", () -> "10");
        registry.add("app.auth.jwks-min-refresh-seconds", () -> "1");
    }

    @Test
    void picksUpRotatedKeysWithoutARestart() throws Exception {
        // A token signed with a key the service has never seen.
        String rotated = ROTATED.withScopes("task-service:tasks:read");
        assertEquals(401, send("GET", "/v1/tasks", null, "Authorization", "Bearer " + rotated).statusCode());

        IDP.serve(ROTATED.jwks());
        // The unknown key id triggers a refresh, rate-limited so it cannot be used to hammer the
        // provider; past that window the new key is picked up with no restart and no downtime.
        Thread.sleep(1_500);
        HttpResponse<String> afterRotation = send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + rotated);
        assertEquals(200, afterRotation.statusCode(), afterRotation.body());
    }

    @Test
    void keepsServingWhileTheProviderIsDown() throws Exception {
        String token = ROTATED.withScopes("task-service:tasks:read");
        IDP.serve(ROTATED.jwks());
        Thread.sleep(1_500);
        assertEquals(200, send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token).statusCode());

        // The provider goes away. Cached keys are still good, so this service does not go down too.
        IDP.failWith(503);
        assertEquals(200, send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token).statusCode());
        IDP.failWith(200);
    }
}
