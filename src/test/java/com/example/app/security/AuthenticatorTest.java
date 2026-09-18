package com.example.app.security;

import static com.example.app.testing.Assert.assertContains;
import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertThrows;
import static com.example.app.testing.Assert.assertTrue;

import com.example.app.http.Access;
import com.example.app.observability.Metrics;
import com.example.app.testing.Test;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AuthenticatorTest {

    private static final String ISSUER = "https://issuer.test/realms/demo";
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final String KID = "key-1";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Metrics metrics = new Metrics(clock);
    private final KeyPair pair;
    private final Authenticator authenticator;

    public AuthenticatorTest() throws Exception {
        pair = TokenFixtures.rsa();
        authenticator = Authenticator.of(verifier(), "task-service", 8192, metrics);
    }

    private JwtVerifier verifier() {
        Map<String, VerificationKey> keys = Jwks.parse(TokenFixtures.jwks(KID, pair));
        JwkSource source = new JwkSource() {
            @Override
            public Optional<VerificationKey> find(String kid) {
                return Optional.ofNullable(keys.get(kid));
            }

            @Override
            public int size() {
                return keys.size();
            }
        };
        return new JwtVerifier(source, ISSUER, "task-service", Duration.ofSeconds(60), 8192, clock);
    }

    @Test
    void neverLooksAtATokenOnAPublicRoute() throws Exception {
        // A health probe must cost nothing and must not fail because someone sent a stale header.
        assertEquals(Optional.empty(), authenticator.authorize(Access.PUBLIC, "Bearer garbage"));
        assertEquals(Optional.empty(), authenticator.authorize(Access.PUBLIC, null));
    }

    @Test
    void acceptsAnyValidTokenWhenNoScopeIsRequired() throws Exception {
        Optional<Principal> principal =
                authenticator.authorize(Access.authenticated(), bearer(Map.of("scope", "something:else")));

        assertEquals(Optional.of("user-42"), principal.map(Principal::subject));
    }

    @Test
    void requiresEveryScopeARouteAsksFor() throws Exception {
        Access needsBoth = Access.scope("a", "b");

        assertTrue(authenticator.authorize(needsBoth, bearer(Map.of("scope", "a b c"))).isPresent(),
                "a superset of the required scopes is enough");

        InsufficientScopeException refused = assertThrows(InsufficientScopeException.class,
                () -> authenticator.authorize(needsBoth, bearer(Map.of("scope", "a"))));
        assertEquals(Set.of("a", "b"), refused.required());
        assertEquals("a b", refused.requiredAsParameter());
    }

    @Test
    void treatsANonBearerSchemeAsNoCredentialsAtAll() {
        // Basic auth is not a failed bearer attempt; the caller simply did not present one.
        assertThrows(MissingCredentialsException.class,
                () -> authenticator.authorize(Access.authenticated(), "Basic dXNlcjpwYXNz"));
        assertThrows(MissingCredentialsException.class,
                () -> authenticator.authorize(Access.authenticated(), "Bearer   "));
        assertThrows(MissingCredentialsException.class,
                () -> authenticator.authorize(Access.authenticated(), null));
    }

    @Test
    void acceptsTheSchemeCaseInsensitively() throws Exception {
        assertTrue(authenticator.authorize(Access.authenticated(),
                "bearer " + bearer(Map.of()).substring(7)).isPresent(), "RFC 9110 scheme matching is case-insensitive");
    }

    @Test
    void keepsAnInjectedRealmOutOfTheChallengeSyntax() throws Exception {
        // A quote or a backslash in the realm would break the quoted-string syntax of
        // WWW-Authenticate, and a newline would split the header.
        Authenticator odd = Authenticator.of(verifier(), "ev\"il\\realm\ninjected", 8192, metrics);

        assertEquals("evilrealminjected", odd.realm());
    }

    @Test
    void refusesToBeBuiltWithoutAVerifier() {
        // Enforcing nothing must never be reachable by accident; Authenticator.disabled() is the
        // only way to get there, and it says so in its name.
        assertThrows(NullPointerException.class, () -> Authenticator.of(null, "api", 8192, metrics));
    }

    @Test
    void disabledLetsEverythingThroughWithoutAnIdentity() throws Exception {
        Authenticator off = Authenticator.disabled();

        assertEquals(false, off.enabled());
        assertEquals(Optional.empty(), off.authorize(Access.scope("anything"), null));
    }

    @Test
    void countsOutcomesWithoutRecordingTheToken() throws Exception {
        String token = bearer(Map.of("scope", "a"));
        authenticator.authorize(Access.scope("a"), token);
        try {
            authenticator.authorize(Access.scope("b"), token);
        } catch (InsufficientScopeException expected) {
            // counted below
        }
        try {
            authenticator.authorize(Access.authenticated(), null);
        } catch (MissingCredentialsException expected) {
            // counted below
        }

        String rendered = new String(metrics.render(), java.nio.charset.StandardCharsets.UTF_8);
        assertContains(rendered, "http_server_auth_decisions_total{outcome=\"allowed\"} 1");
        assertContains(rendered, "http_server_auth_decisions_total{outcome=\"insufficient_scope\"} 1");
        assertContains(rendered, "http_server_auth_decisions_total{outcome=\"missing_token\"} 1");
        assertTrue(!rendered.contains(token.substring(0, 20)), "no part of the token becomes a metric");
    }

    private String bearer(Map<String, Object> overrides) throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", "task-service");
        claims.put("sub", "user-42");
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.putAll(overrides);
        return "Bearer " + TokenFixtures.sign(KID, "RS256", pair.getPrivate(), claims);
    }
}
