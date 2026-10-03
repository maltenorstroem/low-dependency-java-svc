package com.example.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.app.testing.HttpTestSupport;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The resource server end to end, against a key server the test controls.
 *
 * <p>Several of these assert behaviour Spring Security and Nimbus already provide — {@code alg:none}
 * is refused, an HMAC signature cannot be verified with a published public key, a tampered payload
 * fails. They are kept anyway: they state what this service promises, not how the library happens
 * to be built, and a configuration change that quietly re-enabled HS256 would otherwise go unnoticed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiTest extends HttpTestSupport {

    private static final StubJwksServer IDP;
    private static final TokenFixtures TOKENS;

    static {
        // Both have to exist before the context is built, since the context is told where the key
        // server is; a @BeforeAll would run too late.
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
        registry.add("app.auth.realm", () -> "api");
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void servesCallersCarryingTheRightScope() throws Exception {
        String write = TOKENS.withScopes("task-service:tasks:read task-service:tasks:write");
        HttpResponse<String> created = send("POST", "/v1/tasks", "{\"title\":\"authorised\"}",
                "Authorization", "Bearer " + write);
        assertEquals(201, created.statusCode(), created.body());

        String id = (String) object(created).get("id");
        assertEquals(200, send("GET", "/v1/tasks/" + id, null,
                "Authorization", "Bearer " + write).statusCode());
        assertEquals(204, send("DELETE", "/v1/tasks/" + id, null,
                "Authorization", "Bearer " + write, "If-Match", "\"1\"").statusCode());
    }

    @Test
    void acceptsScopesFromTheArrayValuedClaimToo() throws Exception {
        String token = TOKENS.withScpArray(List.of("task-service:tasks:read"));
        assertEquals(200, send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token).statusCode());
    }

    @Test
    void headInheritsTheScopeOfGet() throws Exception {
        String read = TOKENS.withScopes("task-service:tasks:read");
        assertEquals(200, send("HEAD", "/v1/tasks", null, "Authorization", "Bearer " + read).statusCode());
        assertEquals(401, send("HEAD", "/v1/tasks", null).statusCode());
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void refusesAnonymousCallers() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/tasks", null);
        Map<?, ?> problem = assertProblem(response, 401);
        // RFC 6750: no credentials means a bare challenge, with no error code to react to.
        assertEquals("Bearer realm=\"api\"", header(response, "WWW-Authenticate"));
        assertEquals("about:blank", problem.get("type"));
    }

    @Test
    void refusesTokensItCannotVerify() throws Exception {
        assertInvalidToken(send("GET", "/v1/tasks", null, "Authorization", "Bearer not.a.token"));
        assertInvalidToken(send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.signedWithAnUnknownKey(TOKENS.claims().build())));
        assertInvalidToken(send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.tampered(TOKENS.withScopes("task-service:tasks:read"))));
    }

    @Test
    void refusesUnsignedTokens() throws Exception {
        String unsigned = TOKENS.unsigned(TOKENS.claims().claim("scope", "task-service:tasks:read").build());
        assertInvalidToken(send("GET", "/v1/tasks", null, "Authorization", "Bearer " + unsigned));
    }

    @Test
    void refusesThePublicKeyUsedAsAnHmacSecret() throws Exception {
        String confused = TOKENS.signedWithPublicKeyAsHmacSecret(
                TOKENS.claims().claim("scope", "task-service:tasks:read").build());
        assertInvalidToken(send("GET", "/v1/tasks", null, "Authorization", "Bearer " + confused));
    }

    @Test
    void refusesExpiredTokensButAllowsTheConfiguredLeeway() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet expired = TOKENS.claims()
                .expirationTime(Date.from(now.minusSeconds(3_600)))
                .claim("scope", "task-service:tasks:read").build();
        assertInvalidToken(send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.signedWithRsa(expired)));

        // Inside the 60s leeway, so a small clock difference between hosts is not an outage.
        // issueTime has to move back too: a token that expires before it was issued is refused
        // outright, whatever the leeway.
        JWTClaimsSet justExpired = TOKENS.claims()
                .issueTime(Date.from(now.minusSeconds(120)))
                .expirationTime(Date.from(now.minusSeconds(10)))
                .claim("scope", "task-service:tasks:read").build();
        HttpResponse<String> inLeeway = send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.signedWithRsa(justExpired));
        assertEquals(200, inLeeway.statusCode(),
                () -> header(inLeeway, "WWW-Authenticate") + " / " + inLeeway.body());
    }

    @Test
    void refusesTokensForAnotherIssuerOrAudience() throws Exception {
        JWTClaimsSet otherIssuer = TOKENS.claims().issuer("https://evil.example.com")
                .claim("scope", "task-service:tasks:read").build();
        assertInvalidToken(send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.signedWithRsa(otherIssuer)));

        JWTClaimsSet otherAudience = TOKENS.claims().audience("another-service")
                .claim("scope", "task-service:tasks:read").build();
        assertInvalidToken(send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.signedWithRsa(otherAudience)));
    }

    @Test
    void acceptsAnAudienceArrayThatContainsThisService() throws Exception {
        JWTClaimsSet many = TOKENS.claims()
                .audience(List.of("another-service", TokenFixtures.AUDIENCE))
                .claim("scope", "task-service:tasks:read").build();
        assertEquals(200, send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.signedWithRsa(many)).statusCode());
    }

    @Test
    void refusesTokensWithoutASubject() throws Exception {
        JWTClaimsSet anonymous = TOKENS.claims().subject(null)
                .claim("scope", "task-service:tasks:read").build();
        assertInvalidToken(send("GET", "/v1/tasks", null,
                "Authorization", "Bearer " + TOKENS.signedWithRsa(anonymous)));
    }

    @Test
    void refusesOversizedTokens() throws Exception {
        // Over the 8 KiB token cap but under the 16 KiB header cap, so the service refuses it
        // rather than Tomcat rejecting the request line before it is ever seen.
        String huge = "x".repeat(10_000);
        assertInvalidToken(send("GET", "/v1/tasks", null, "Authorization", "Bearer " + huge));
    }

    @Test
    void refusesSchemesOtherThanBearer() throws Exception {
        assertEquals(401, send("GET", "/v1/tasks", null, "Authorization", "Basic dXNlcjpwdw==").statusCode());
    }

    @Test
    void namesTheScopeAMissingCallerWouldHaveNeeded() throws Exception {
        String readOnly = TOKENS.withScopes("task-service:tasks:read");
        HttpResponse<String> response = send("POST", "/v1/tasks", "{\"title\":\"nope\"}",
                "Authorization", "Bearer " + readOnly);
        Map<?, ?> problem = assertProblem(response, 403);

        assertEquals("https://example.com/problems/insufficient-scope", problem.get("type"));
        assertEquals(List.of("task-service:tasks:write"), problem.get("requiredScopes"));
        assertTrue(header(response, "WWW-Authenticate").contains("error=\"insufficient_scope\""),
                header(response, "WWW-Authenticate"));
        assertTrue(header(response, "WWW-Authenticate").contains("scope=\"task-service:tasks:write\""),
                header(response, "WWW-Authenticate"));
    }

    @Test
    void streamsNauticalFlagsOnlyToCallersWithTheFlagsScope() throws Exception {
        String flags = TOKENS.withScopes("task-service:flags:read");
        HttpResponse<String> streamed = send("GET", "/v1/nautical-flags?text=A&intervalMs=50", null,
                "Authorization", "Bearer " + flags, "Accept", "text/event-stream");
        assertEquals(200, streamed.statusCode(), streamed.body());

        String tasks = TOKENS.withScopes("task-service:tasks:read");
        HttpResponse<String> refused = send("GET", "/v1/nautical-flags?text=A", null,
                "Authorization", "Bearer " + tasks);
        assertEquals(List.of("task-service:flags:read"), assertProblem(refused, 403).get("requiredScopes"));
        assertEquals(401, send("GET", "/v1/nautical-flags?text=A", null).statusCode());
    }

    @Test
    void neverEchoesTheTokenBack() throws Exception {
        String token = TOKENS.withScopes("task-service:tasks:read");
        HttpResponse<String> forbidden = send("POST", "/v1/tasks", "{\"title\":\"x\"}",
                "Authorization", "Bearer " + token);
        assertFalse(forbidden.body().contains(token), "the token must never appear in a response");
        assertFalse(String.valueOf(forbidden.headers().map()).contains(token),
                "the token must never appear in a header");
    }

    @Test
    void doesNotRevealWhetherAResourceExistsToAnUnauthenticatedCaller() throws Exception {
        String write = TOKENS.withScopes("task-service:tasks:read task-service:tasks:write");
        HttpResponse<String> created = send("POST", "/v1/tasks", "{\"title\":\"secret\"}",
                "Authorization", "Bearer " + write);
        String id = (String) object(created).get("id");

        // Both the real id and a made-up one answer 401, so no existence can be inferred.
        assertEquals(401, send("GET", "/v1/tasks/" + id, null).statusCode());
        assertEquals(401, send("GET", "/v1/tasks/00000000-0000-7000-8000-000000000000", null).statusCode());
    }

    // ---------------------------------------------------------------- public surface

    @Test
    void leavesActuatorProbesAndPreflightOpen() throws Exception {
        assertEquals(200, send("GET", "/actuator/health/liveness", null).statusCode());
        assertEquals(200, send("GET", "/actuator/health/readiness", null).statusCode());
        // A preflight carries no Authorization header by definition.
        assertTrue(send("OPTIONS", "/v1/tasks", null).statusCode() < 400);
    }

    @Test
    void countsAuthenticationOutcomesWithABoundedLabelSet() throws Exception {
        send("GET", "/v1/tasks", null); // missing_token
        send("GET", "/v1/tasks", null, "Authorization", "Bearer rubbish"); // invalid_token
        String readOnly = TOKENS.withScopes("task-service:tasks:read");
        send("GET", "/v1/tasks", null, "Authorization", "Bearer " + readOnly); // allowed
        send("POST", "/v1/tasks", "{\"title\":\"x\"}",
                "Authorization", "Bearer " + readOnly); // insufficient_scope

        String metrics = send("GET", "/metrics", null).body();
        for (String outcome : new String[] {"missing_token", "invalid_token", "allowed", "insufficient_scope"}) {
            assertTrue(metrics.contains("outcome=\"" + outcome + "\""),
                    () -> "missing outcome " + outcome);
        }
        // Spring Security publishes no meters of its own, and Nimbus does not report how many keys
        // it holds; without this gauge a provider that starts serving an empty key set looks fine
        // until every token fails.
        assertTrue(metrics.contains("auth_jwks_keys"), "cached key count");
    }

    private void assertInvalidToken(HttpResponse<String> response) throws Exception {
        Map<?, ?> problem = assertProblem(response, 401);
        assertEquals("Unauthorized", problem.get("title"));
        assertEquals("https://example.com/problems/invalid-token", problem.get("type"));
        assertTrue(header(response, "WWW-Authenticate").contains("error=\"invalid_token\""),
                header(response, "WWW-Authenticate"));
    }
}
