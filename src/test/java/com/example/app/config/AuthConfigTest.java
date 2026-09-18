package com.example.app.config;

import static com.example.app.testing.Assert.assertContains;
import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertThrows;
import static com.example.app.testing.Assert.assertTrue;

import com.example.app.testing.Test;
import java.util.HashMap;
import java.util.Map;

public class AuthConfigTest {

    private static final String ISSUER = "https://issuer.example.com/realms/demo";
    private static final String JWKS = "https://issuer.example.com/realms/demo/protocol/openid-connect/certs";

    @Test
    void isOffWhenNothingIsConfigured() {
        // What keeps ./build.sh run and the container smoke test working with no identity
        // provider anywhere in sight.
        assertEquals(false, Config.fromEnvironment(Map.of()).auth().enabled());
    }

    @Test
    void turnsItselfOnAsSoonAsAnIssuerIsConfigured() {
        // The point of the rule: you cannot point this at a provider and then forget to enforce.
        assertTrue(Config.fromEnvironment(env(Map.of())).auth().enabled(), "configuring an issuer enables auth");
    }

    @Test
    void canStillBeTurnedOffExplicitly() {
        assertEquals(false, Config.fromEnvironment(env(Map.of("APP_AUTH_ENABLED", "false"))).auth().enabled());
    }

    @Test
    void refusesToStartWhenEnabledButIncomplete() {
        assertContains(assertThrows(ConfigException.class,
                () -> Config.fromEnvironment(Map.of("APP_AUTH_ENABLED", "true"))).getMessage(),
                "APP_AUTH_ISSUER");

        Map<String, String> noJwks = new HashMap<>(env(Map.of()));
        noJwks.remove("APP_AUTH_JWKS_URL");
        assertContains(assertThrows(ConfigException.class,
                () -> Config.fromEnvironment(noJwks)).getMessage(), "APP_AUTH_JWKS_URL");
    }

    @Test
    void refusesKeysFetchedOverPlainHttp() {
        // Signing keys taken over plain HTTP could be swapped in flight, which defeats the whole
        // mechanism.
        assertContains(rejection(Map.of("APP_AUTH_JWKS_URL", "http://issuer.example.com/certs")).getMessage(),
                "https");
    }

    @Test
    void allowsPlainHttpOnLoopbackSoTestsCanRunAStubProvider() {
        assertTrue(Config.fromEnvironment(env(Map.of("APP_AUTH_JWKS_URL", "http://127.0.0.1:9000/jwks")))
                .auth().enabled(), "a loopback key server is allowed");
    }

    @Test
    void rejectsAMalformedJwksUrl() {
        assertContains(rejection(Map.of("APP_AUTH_JWKS_URL", "not-a-url")).getMessage(), "APP_AUTH_JWKS_URL");
    }

    @Test
    void rangeChecksEveryTuningKnob() {
        assertContains(rejection(Map.of("APP_AUTH_CLOCK_LEEWAY_SECONDS", "99999")).getMessage(), "between");
        assertContains(rejection(Map.of("APP_AUTH_MAX_TOKEN_BYTES", "12")).getMessage(), "between");
        assertContains(rejection(Map.of("APP_AUTH_ENABLED", "perhaps")).getMessage(), "true or false");
    }

    @Test
    void appliesTheDocumentedDefaults() {
        AuthConfig auth = Config.fromEnvironment(env(Map.of())).auth();

        assertEquals("task-service:", auth.scopePrefix());
        assertEquals("api", auth.realm());
        assertEquals(300L, auth.jwksTtl().toSeconds());
        assertEquals(30L, auth.jwksMinRefresh().toSeconds());
        assertEquals(3_600L, auth.jwksMaxStale().toSeconds());
        assertEquals(60L, auth.clockLeeway().toSeconds());
        assertEquals(8_192, auth.maxTokenBytes());
    }

    private static ConfigException rejection(Map<String, String> overrides) {
        Map<String, String> env = env(overrides);
        return assertThrows(ConfigException.class, () -> Config.fromEnvironment(env));
    }

    private static Map<String, String> env(Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>(Map.of(
                "APP_AUTH_ISSUER", ISSUER,
                "APP_AUTH_AUDIENCE", "task-service",
                "APP_AUTH_JWKS_URL", JWKS));
        env.putAll(overrides);
        return env;
    }
}
