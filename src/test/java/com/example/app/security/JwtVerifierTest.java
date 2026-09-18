package com.example.app.security;

import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertThrows;
import static com.example.app.testing.Assert.assertTrue;

import com.example.app.security.InvalidTokenException.Reason;
import com.example.app.testing.Test;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings("unchecked")
public class JwtVerifierTest {

    private static final String ISSUER = "https://issuer.example.com/realms/demo";
    private static final String AUDIENCE = "task-service";
    private static final String KID = "key-1";
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** A key source holding exactly what the test put in it: no HTTP, no cache, no surprises. */
    private record FixedKeys(Map<String, VerificationKey> keys) implements JwkSource {
        @Override
        public Optional<VerificationKey> find(String kid) {
            if (kid != null) {
                return Optional.ofNullable(keys.get(kid));
            }
            return keys.size() == 1 ? keys.values().stream().findFirst() : Optional.empty();
        }

        @Override
        public int size() {
            return keys.size();
        }
    }

    @Test
    void verifiesRs256AndExtractsThePrincipal() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(), claims(Map.of(
                "scope", "task-service:tasks:read task-service:tasks:write",
                "azp", "reporting-job")));

        Principal principal = verifier(pair).verify(token);

        assertEquals("user-42", principal.subject());
        assertEquals(ISSUER, principal.issuer());
        assertEquals(Optional.of("reporting-job"), principal.clientId());
        assertEquals(Set.of("task-service:tasks:read", "task-service:tasks:write"), principal.scopes());
    }

    @Test
    void verifiesEs256() throws Exception {
        // The JWS signature is a raw r||s pair, not a DER sequence; this fails loudly if the
        // verifier ever reaches for plain "SHA256withECDSA".
        KeyPair pair = TokenFixtures.ec("secp256r1");
        String token = TokenFixtures.sign(KID, "ES256", pair.getPrivate(), claims(Map.of()));

        assertEquals("user-42", verifier(pair).verify(token).subject());
    }

    @Test
    void verifiesEs512() throws Exception {
        KeyPair pair = TokenFixtures.ec("secp521r1");
        String token = TokenFixtures.sign(KID, "ES512", pair.getPrivate(), claims(Map.of()));

        assertEquals("user-42", verifier(pair).verify(token).subject());
    }

    @Test
    void rejectsAlgNone() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.forge(
                Map.of("alg", "none", "typ", "JWT", "kid", KID), claims(Map.of()), "AA");

        assertEquals(Reason.BAD_ALGORITHM, rejection(pair, token).cause());
    }

    /**
     * The classic algorithm-confusion attack: take the RSA public key everyone can fetch from the
     * JWKS, use its encoding as an HMAC secret, and re-sign. It must never get as far as a key
     * lookup.
     */
    @Test
    void rejectsHs256SignedWithThePublicKeyAsTheSecret() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String signingInput = TokenFixtures.segment(Map.of("alg", "HS256", "typ", "JWT", "kid", KID))
                + "." + TokenFixtures.segment(claims(Map.of()));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(pair.getPublic().getEncoded(), "HmacSHA256"));
        String token = signingInput + "." + TokenFixtures.URL.encodeToString(
                mac.doFinal(signingInput.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

        assertEquals(Reason.BAD_ALGORITHM, rejection(pair, token).cause());
    }

    @Test
    void rejectsHeaderAlgorithmThatDisagreesWithTheKey() throws Exception {
        // An RSA key, but the token claims ES256. The algorithm comes from the key, so this is a
        // mismatch rather than an invitation to try ECDSA against an RSA key.
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(
                Map.of("alg", "ES256", "typ", "JWT", "kid", KID), claims(Map.of()),
                pair.getPrivate(), "SHA256withRSA");

        assertEquals(Reason.BAD_ALGORITHM, rejection(pair, token).cause());
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(), claims(Map.of()));
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + TokenFixtures.segment(claims(Map.of("sub", "admin"))) + "." + parts[2];

        assertEquals(Reason.BAD_SIGNATURE, rejection(pair, tampered).cause());
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        KeyPair published = TokenFixtures.rsa();
        KeyPair attacker = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", attacker.getPrivate(), claims(Map.of()));

        assertEquals(Reason.BAD_SIGNATURE, rejection(published, token).cause());
    }

    @Test
    void rejectsExpiredButAllowsClockSkewWithinTheLeeway() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String longGone = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("exp", NOW.minusSeconds(3_600).getEpochSecond())));
        String justExpired = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("exp", NOW.minusSeconds(30).getEpochSecond())));

        assertEquals(Reason.EXPIRED, rejection(pair, longGone).cause());
        assertEquals("user-42", verifier(pair).verify(justExpired).subject()); // leeway is 60s
    }

    @Test
    void rejectsTokensThatAreNotYetValid() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String notYet = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("nbf", NOW.plusSeconds(600).getEpochSecond())));
        String future = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("iat", NOW.plusSeconds(600).getEpochSecond())));

        assertEquals(Reason.NOT_YET_VALID, rejection(pair, notYet).cause());
        assertEquals(Reason.NOT_YET_VALID, rejection(pair, future).cause());
    }

    @Test
    void requiresAnExpiryClaim() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        Map<String, Object> claims = claims(Map.of());
        claims.remove("exp");
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(), claims);

        assertEquals(Reason.MISSING_CLAIM, rejection(pair, token).cause());
    }

    @Test
    void requiresTheConfiguredIssuerAndAudience() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String wrongIssuer = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("iss", "https://evil.example.com")));
        String wrongAudience = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("aud", "some-other-service")));

        assertEquals(Reason.BAD_ISSUER, rejection(pair, wrongIssuer).cause());
        assertEquals(Reason.BAD_AUDIENCE, rejection(pair, wrongAudience).cause());
    }

    @Test
    void acceptsAudienceAsAnArray() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("aud", List.of("other-service", AUDIENCE))));

        assertEquals("user-42", verifier(pair).verify(token).subject());
    }

    @Test
    void rejectsUnknownKeyId() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign("rotated-away", "RS256", pair.getPrivate(), claims(Map.of()));

        assertEquals(Reason.UNKNOWN_KEY, rejection(pair, token).cause());
    }

    @Test
    void reportsKeysUnavailableWhenTheKeySetIsEmpty() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(), claims(Map.of()));
        JwtVerifier verifier = new JwtVerifier(new FixedKeys(Map.of()), ISSUER, AUDIENCE,
                Duration.ofSeconds(60), 8192, clock);

        assertEquals(Reason.KEYS_UNAVAILABLE,
                assertThrows(InvalidTokenException.class, () -> verifier.verify(token)).cause());
    }

    @Test
    void rejectsStructurallyMalformedTokens() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        for (String bad : List.of("", "onlyone", "two.parts", "a.b.c.d", "..", "not-base64!.x.y")) {
            assertEquals(Reason.MALFORMED, rejection(pair, bad).cause());
        }
    }

    @Test
    void rejectsCriticalHeaderExtensions() throws Exception {
        // RFC 7515 says a "crit" we do not understand must be rejected, and we understand none.
        KeyPair pair = TokenFixtures.rsa();
        Map<String, Object> header = new LinkedHashMap<>(
                Map.of("alg", "RS256", "typ", "JWT", "kid", KID));
        header.put("crit", List.of("exp"));
        String token = TokenFixtures.sign(header, claims(Map.of()), pair.getPrivate(), "SHA256withRSA");

        assertEquals(Reason.BAD_ALGORITHM, rejection(pair, token).cause());
    }

    @Test
    void rejectsAnIdTokenReplayedAsAnAccessToken() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(
                Map.of("alg", "RS256", "typ", "JWE", "kid", KID), claims(Map.of()),
                pair.getPrivate(), "SHA256withRSA");

        assertEquals(Reason.MALFORMED, rejection(pair, token).cause());
    }

    @Test
    void rejectsOversizedTokens() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("padding", "x".repeat(20_000))));

        assertEquals(Reason.TOO_LARGE, rejection(pair, token).cause());
    }

    @Test
    void rejectsDuplicateClaims() throws Exception {
        // Leans on the project's strict JSON parser: two exp members is an attack, not a quirk,
        // and nobody has to decide which one wins.
        KeyPair pair = TokenFixtures.rsa();
        String payload = "{\"sub\":\"user-42\",\"exp\":99999999999,\"exp\":1}";
        String header = TokenFixtures.segment(Map.of("alg", "RS256", "typ", "JWT", "kid", KID));
        String token = header + "."
                + TokenFixtures.URL.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".AA";

        assertEquals(Reason.MALFORMED, rejection(pair, token).cause());
    }

    @Test
    void rejectsAbsurdTimestampsInsteadOfFailingWithAServerError() throws Exception {
        // A value this large truncates into a perfectly plausible instant if converted carelessly,
        // and blows up Instant.ofEpochSecond if not. Either way the caller must see a 401, not a 500.
        KeyPair pair = TokenFixtures.rsa();
        for (String exp : List.of("999999999999999999999999999999", "-999999999999999999999999999999")) {
            String payload = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE
                    + "\",\"sub\":\"user-42\",\"exp\":" + exp + "}";
            String token = TokenFixtures.sign(Map.of("alg", "RS256", "typ", "JWT", "kid", KID),
                    com.example.app.json.Json.parse(payload) instanceof Map<?, ?> m
                            ? new LinkedHashMap<>((Map<String, Object>) m) : null,
                    pair.getPrivate(), "SHA256withRSA");
            assertEquals(Reason.MALFORMED, rejection(pair, token).cause());
        }
    }

    @Test
    void acceptsAFractionalNumericDate() throws Exception {
        // RFC 7519 allows a non-integer NumericDate; the fraction is simply dropped.
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("exp", new java.math.BigDecimal(NOW.plusSeconds(300).getEpochSecond() + ".75"))));

        assertEquals("user-42", verifier(pair).verify(token).subject());
    }

    @Test
    void readsScopesFromEitherClaimShape() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String spaceDelimited = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("scope", "a b  c")));
        String array = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("scp", List.of("d", "e"))));
        String both = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("scope", "a", "scp", List.of("b"))));

        assertEquals(Set.of("a", "b", "c"), verifier(pair).verify(spaceDelimited).scopes());
        assertEquals(Set.of("d", "e"), verifier(pair).verify(array).scopes());
        assertEquals(Set.of("a", "b"), verifier(pair).verify(both).scopes());
    }

    @Test
    void ignoresMalformedScopesWithoutFailingTheToken() throws Exception {
        KeyPair pair = TokenFixtures.rsa();
        String token = TokenFixtures.sign(KID, "RS256", pair.getPrivate(),
                claims(Map.of("scope", "good \"quoted\" " + "x".repeat(200) + " also-good")));

        Set<String> scopes = verifier(pair).verify(token).scopes();
        assertEquals(Set.of("good", "also-good"), scopes);
    }

    @Test
    void skipsWeakAndUnusableKeysInTheKeySet() throws Exception {
        String document = com.example.app.json.Json.write(Map.of("keys", List.of(
                Map.of("kty", "oct", "kid", "symmetric", "k", "AAAA"),
                TokenFixtures.jwk("weak", weakRsa()),
                Map.of("kty", "OKP", "kid", "ed25519", "crv", "Ed25519", "x", "AAAA"))));

        assertTrue(Jwks.parse(document).isEmpty(), "no symmetric, weak or unknown key is usable");
    }

    // ------------------------------------------------------------------ helpers

    private static KeyPair weakRsa() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private JwtVerifier verifier(KeyPair pair) {
        Map<String, VerificationKey> keys = Jwks.parse(TokenFixtures.jwks(KID, pair));
        return new JwtVerifier(new FixedKeys(keys), ISSUER, AUDIENCE, Duration.ofSeconds(60), 8192, clock);
    }

    private InvalidTokenException rejection(KeyPair pair, String token) {
        JwtVerifier verifier = verifier(pair);
        return assertThrows(InvalidTokenException.class, () -> verifier.verify(token));
    }

    /** A valid token body, with {@code overrides} replacing individual claims. */
    private static Map<String, Object> claims(Map<String, Object> overrides) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", "user-42");
        claims.put("iat", NOW.minusSeconds(60).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.putAll(overrides);
        return claims;
    }
}
