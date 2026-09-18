package com.example.app.security;

import static com.example.app.testing.Assert.assertContains;
import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertTrue;

import com.example.app.Application;
import com.example.app.config.Config;
import com.example.app.json.Json;
import com.example.app.observability.Log;
import com.example.app.testing.Test;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole feature, black box: a real server with authentication enabled, a real stub identity
 * provider, and real signed tokens over real HTTP.
 */
public class AuthApiTest {

    private static final String ISSUER = "https://issuer.test/realms/demo";
    private static final String AUDIENCE = "task-service";
    private static final String KID = "test-key";

    private static final StubJwksServer IDP;
    private static final KeyPair SIGNING_KEY;
    private static final Application APP;

    static {
        try {
            Log.setThreshold(Level.OFF);
            SIGNING_KEY = TokenFixtures.rsa();
            IDP = new StubJwksServer();
            IDP.serve(TokenFixtures.jwks(KID, SIGNING_KEY));
            APP = new Application(Config.fromEnvironment(Map.of(
                    "APP_HOST", "127.0.0.1",
                    "APP_PORT", "0",
                    "APP_DRAIN_DELAY_SECONDS", "0",
                    "APP_AUTH_ISSUER", ISSUER,
                    "APP_AUTH_AUDIENCE", AUDIENCE,
                    "APP_AUTH_JWKS_URL", IDP.url().toString(),
                    "APP_AUTH_REALM", "task-service")));
            APP.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Test
    void refusesAnonymousAccessWithoutNamingAnErrorCode() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/tasks", null);

        assertEquals(401, response.statusCode());
        // RFC 6750 section 3.1: with no credentials at all there is no failed attempt to report.
        assertEquals("Bearer realm=\"task-service\"", header(response, "WWW-Authenticate"));
        assertEquals("application/problem+json", header(response, "Content-Type"));
        assertEquals(401, ((Number) object(response).get("status")).intValue());
        assertEquals("Unauthorized", object(response).get("title"));
    }

    @Test
    void refusesAGarbageTokenAsInvalidWithoutSayingWhy() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/tasks", null,
                "Authorization", "Bearer not.a.token");

        assertEquals(401, response.statusCode());
        assertContains(header(response, "WWW-Authenticate"), "error=\"invalid_token\"");
        assertContains(response.body(), "invalid-token");
    }

    @Test
    void tellsAnHonestClientThatItsTokenExpired() throws Exception {
        String token = token(Map.of("exp", Instant.now().minusSeconds(3_600).getEpochSecond()));

        HttpResponse<String> response = send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token);

        assertEquals(401, response.statusCode());
        assertContains(header(response, "WWW-Authenticate"), "The access token is expired");
    }

    @Test
    void refusesAForgedTokenWithoutRevealingWhichCheckFailed() throws Exception {
        KeyPair attacker = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", attacker.getPrivate(), claims(Map.of()));

        HttpResponse<String> response = send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token);

        assertEquals(401, response.statusCode());
        assertContains(header(response, "WWW-Authenticate"), "The access token is not valid");
    }

    @Test
    void refusesAValidTokenThatLacksTheScopeAndSaysWhichOneIsNeeded() throws Exception {
        String token = token(Map.of("scope", "task-service:tasks:read"));

        HttpResponse<String> response = send("POST", "/v1/tasks", "{\"title\":\"write something\"}",
                "Authorization", "Bearer " + token);

        assertEquals(403, response.statusCode());
        String challenge = header(response, "WWW-Authenticate");
        assertContains(challenge, "error=\"insufficient_scope\"");
        assertContains(challenge, "scope=\"task-service:tasks:write\"");
        assertEquals(List.of("task-service:tasks:write"), object(response).get("requiredScopes"));
        assertEquals("Forbidden", object(response).get("title"));
    }

    @Test
    void allowsTheFullLifecycleWithTheRightScopes() throws Exception {
        String token = token(Map.of("scope", "task-service:tasks:read task-service:tasks:write"));
        String auth = "Bearer " + token;

        HttpResponse<String> created = send("POST", "/v1/tasks", "{\"title\":\"buy milk\"}", "Authorization", auth);
        assertEquals(201, created.statusCode());

        String location = header(created, "Location");
        HttpResponse<String> read = send("GET", location, null, "Authorization", auth);
        assertEquals(200, read.statusCode());
        assertEquals("buy milk", object(read).get("title"));

        HttpResponse<String> deleted = send("DELETE", location, null,
                "Authorization", auth, "If-Match", header(read, "ETag"));
        assertEquals(204, deleted.statusCode());
    }

    @Test
    void headInheritsTheScopeOfTheGetItFallsBackTo() throws Exception {
        assertEquals(401, send("HEAD", "/v1/tasks", null).statusCode());

        HttpResponse<String> allowed = send("HEAD", "/v1/tasks", null,
                "Authorization", "Bearer " + token(Map.of("scope", "task-service:tasks:read")));
        assertEquals(200, allowed.statusCode());
    }

    @Test
    void keepsTheOperationalEndpointsPublic() throws Exception {
        // Probes hold no token, the metrics scraper holds no token, and the contract has to be
        // readable before a client knows how to get one.
        for (String path : List.of("/health/live", "/health/ready", "/metrics", "/openapi.yaml")) {
            assertEquals(200, send("GET", path, null).statusCode());
        }
    }

    @Test
    void answersPreflightWithoutCredentials() throws Exception {
        // A CORS preflight cannot carry an Authorization header, so OPTIONS must stay public.
        HttpResponse<String> response = send("OPTIONS", "/v1/tasks", null);

        assertEquals(204, response.statusCode());
        assertContains(header(response, "Allow"), "POST");
    }

    @Test
    void doesNotRevealWhetherAnUnauthenticatedCallersResourceExists() throws Exception {
        // Both ids match the same route template, so both stop at authentication. The 404 for a
        // genuinely missing task comes from the handler, which never runs for an anonymous caller.
        String real;
        String auth = "Bearer " + token(Map.of("scope", "task-service:tasks:read task-service:tasks:write"));
        real = header(send("POST", "/v1/tasks", "{\"title\":\"real\"}", "Authorization", auth), "Location");

        assertEquals(401, send("GET", real, null).statusCode());
        assertEquals(401, send("GET", "/v1/tasks/00000000-0000-7000-8000-000000000000", null).statusCode());

        // An unmatched path is still an honest 404: the route table is published anyway.
        assertEquals(404, send("GET", "/nope", null).statusCode());
    }

    @Test
    void neverEchoesTheTokenBackToTheCaller() throws Exception {
        String token = token(Map.of("exp", Instant.now().minusSeconds(3_600).getEpochSecond()));

        HttpResponse<String> response = send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token);

        assertTrue(!response.body().contains(token), "the problem body does not quote the token");
        assertTrue(!String.valueOf(response.headers().map()).contains(token),
                "no response header quotes the token");
    }

    @Test
    void countsAuthorizationOutcomesWithoutUnboundedLabels() throws Exception {
        send("GET", "/v1/tasks", null);
        send("GET", "/v1/tasks", null, "Authorization", "Bearer " + token(Map.of("scope", "task-service:tasks:read")));

        String metrics = send("GET", "/metrics", null).body();
        assertContains(metrics, "http_server_auth_decisions_total{outcome=\"allowed\"}");
        assertContains(metrics, "http_server_auth_decisions_total{outcome=\"missing_token\"}");
        assertTrue(!metrics.contains("sub=\"") && !metrics.contains("kid=\""),
                "no subject or key id ever becomes a metric label");
    }

    @Test
    void asksTheCallerToRetryWhenTheIdentityProviderIsUnreachable() throws Exception {
        // Its own server and its own app: this test breaks the identity provider, which the
        // others rely on working.
        try (StubJwksServer broken = new StubJwksServer()) {
            broken.failWith(500);
            Application app = new Application(Config.fromEnvironment(Map.of(
                    "APP_HOST", "127.0.0.1",
                    "APP_PORT", "0",
                    "APP_DRAIN_DELAY_SECONDS", "0",
                    "APP_AUTH_ISSUER", ISSUER,
                    "APP_AUTH_AUDIENCE", AUDIENCE,
                    "APP_AUTH_JWKS_URL", broken.url().toString())));
            app.start();
            try {
                HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + app.port() + "/v1/tasks"))
                        .header("Authorization", "Bearer " + token(Map.of()))
                        .timeout(Duration.ofSeconds(10))
                        .build(), BodyHandlers.ofString());

                // Not 401: the token may well be fine, we simply cannot check it yet.
                assertEquals(503, response.statusCode());
                assertEquals("5", response.headers().firstValue("Retry-After").orElse(null));
            } finally {
                app.stop();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String token(Map<String, Object> overrides) throws Exception {
        return TokenFixtures.sign(KID, "RS256", SIGNING_KEY.getPrivate(), claims(overrides));
    }

    private static Map<String, Object> claims(Map<String, Object> overrides) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", "user-42");
        claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
        claims.putAll(overrides);
        return claims;
    }

    private static HttpResponse<String> send(String method, String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + APP.port() + path)).timeout(Duration.ofSeconds(10));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        builder.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static Map<?, ?> object(HttpResponse<String> response) {
        return (Map<?, ?>) Json.parse(response.body());
    }
}
