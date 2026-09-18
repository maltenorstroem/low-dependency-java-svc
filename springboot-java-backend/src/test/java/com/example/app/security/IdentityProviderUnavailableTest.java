package com.example.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.app.testing.HttpTestSupport;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * When the keys cannot be fetched at all, a caller gets 503 and not 401.
 *
 * <p>The distinction is the point: 401 tells a client its token is bad and to go and get another,
 * which will not help and puts more load on an identity provider that is already struggling. 503
 * with Retry-After says the fault is on this side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityProviderUnavailableTest extends HttpTestSupport {

    private static final TokenFixtures TOKENS;

    static {
        try {
            TOKENS = new TokenFixtures();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.issuer", () -> TokenFixtures.ISSUER);
        registry.add("app.auth.audience", () -> TokenFixtures.AUDIENCE);
        // Nothing is listening here.
        registry.add("app.auth.jwks-url", () -> "http://127.0.0.1:1/jwks");
        registry.add("app.auth.enabled", () -> "true");
        registry.add("app.auth.jwks-timeout-seconds", () -> "1");
    }

    @Test
    void answers503RatherThanBlamingTheCaller() throws Exception {
        String token = TOKENS.withScopes("task-service:tasks:read");
        HttpResponse<String> response = send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token);

        assertEquals(503, response.statusCode(), response.body());
        assertEquals("5", header(response, "Retry-After"));
        assertProblem(response, 503);
    }

    @Test
    void stillAnswersItsProbesWhileTheProviderIsUnreachable() throws Exception {
        assertEquals(200, send("GET", "/actuator/health/liveness", null).statusCode());
    }
}
