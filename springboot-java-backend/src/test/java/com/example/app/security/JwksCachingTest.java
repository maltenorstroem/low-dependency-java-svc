package com.example.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.app.testing.HttpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Keys are fetched once and reused. Its own context and its own key server, because the rotation
 * and outage cases deliberately change what the provider serves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwksCachingTest extends HttpTestSupport {

    private static final StubJwksServer IDP;
    private static final TokenFixtures TOKENS;

    static {
        try {
            TOKENS = new TokenFixtures();
            IDP = new StubJwksServer();
            IDP.serve(TOKENS.jwks());
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
    }

    @Test
    void fetchesKeysOnceAndServesFromCache() throws Exception {
        String token = TOKENS.withScopes("task-service:tasks:read");
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token).statusCode());
        }
        assertTrue(IDP.requestCount() <= 2,
                "the key document is cached, not fetched per request: " + IDP.requestCount());
    }
}
