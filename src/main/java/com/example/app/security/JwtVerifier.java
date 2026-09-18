package com.example.app.security;

import com.example.app.security.InvalidTokenException.Reason;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies a bearer token and turns it into a {@link Principal}.
 *
 * <p>The order of the checks is the design. Cheap, non-cryptographic rejections come first so a
 * flood of junk costs little; the signature is verified before <em>any</em> claim is read, so no
 * decision is ever made on unauthenticated data; and the algorithm used comes from the key, not
 * from the attacker-supplied header.
 */
public final class JwtVerifier {

    /**
     * {@code none} and every {@code HS*} are absent on purpose. An attacker who re-signs a token
     * with HMAC, keyed on the RSA public key they fetched from the JWKS, is stopped here — and
     * again by {@link Jwks}, which never produces a symmetric key to use.
     */
    private static final Set<String> ALLOWED_ALGORITHMS =
            Set.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512");

    private static final Set<String> ALLOWED_TYPES = Set.of("jwt", "at+jwt", "application/at+jwt");

    private final JwkSource keys;
    private final String issuer;
    private final String audience;
    private final Duration leeway;
    private final int maxTokenBytes;
    private final Clock clock;

    public JwtVerifier(JwkSource keys, String issuer, String audience, Duration leeway,
            int maxTokenBytes, Clock clock) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.leeway = leeway;
        this.maxTokenBytes = maxTokenBytes;
        this.clock = clock;
    }

    public Principal verify(String token) {
        if (token.length() > maxTokenBytes) {
            throw new InvalidTokenException(Reason.TOO_LARGE);
        }
        Jwt jwt = Jwt.decode(token);
        String algorithm = checkHeader(jwt);
        VerificationKey key = keyFor(jwt, algorithm);
        checkSignature(jwt, key, algorithm);
        return checkClaims(jwt);
    }

    /** @return the {@code alg} header, once it is known to be one we are willing to verify. */
    private static String checkHeader(Jwt jwt) {
        if (jwt.header().containsKey("crit")) {
            // RFC 7515 section 4.1.11: we must reject what we do not understand, and we
            // implement no extensions at all.
            throw new InvalidTokenException(Reason.BAD_ALGORITHM);
        }
        Optional<String> type = jwt.headerString("typ");
        if (type.isPresent() && !ALLOWED_TYPES.contains(type.get().toLowerCase(Locale.ROOT))) {
            // An ID token replayed as an access token fails here, and again on the audience.
            throw new InvalidTokenException(Reason.MALFORMED);
        }
        String algorithm = jwt.headerString("alg").orElseThrow(() -> new InvalidTokenException(Reason.BAD_ALGORITHM));
        if (!ALLOWED_ALGORITHMS.contains(algorithm)) {
            throw new InvalidTokenException(Reason.BAD_ALGORITHM);
        }
        return algorithm;
    }

    private VerificationKey keyFor(Jwt jwt, String algorithm) {
        VerificationKey key = keys.find(jwt.headerString("kid").orElse(null))
                .orElseThrow(() -> new InvalidTokenException(
                        keys.size() == 0 ? Reason.KEYS_UNAVAILABLE : Reason.UNKNOWN_KEY));
        if (!key.algorithm().equals(algorithm)) {
            throw new InvalidTokenException(Reason.BAD_ALGORITHM);
        }
        return key;
    }

    private static void checkSignature(Jwt jwt, VerificationKey key, String algorithm) {
        try {
            Signature signature = Signature.getInstance(jcaName(algorithm));
            if (algorithm.startsWith("PS")) {
                signature.setParameter(pssParameters(algorithm));
            }
            signature.initVerify(key.key());
            signature.update(jwt.signingInput());
            if (!signature.verify(jwt.signature())) {
                throw new InvalidTokenException(Reason.BAD_SIGNATURE);
            }
        } catch (GeneralSecurityException e) {
            // A malformed signature encoding lands here too; the client learns nothing either way.
            throw new InvalidTokenException(Reason.BAD_SIGNATURE);
        }
    }

    private Principal checkClaims(Jwt jwt) {
        Instant now = clock.instant();

        String tokenIssuer = jwt.claimString("iss").orElseThrow(() -> new InvalidTokenException(Reason.BAD_ISSUER));
        if (!tokenIssuer.equals(issuer)) {
            throw new InvalidTokenException(Reason.BAD_ISSUER);
        }
        if (!audience.isEmpty() && !jwt.audiences().contains(audience)) {
            throw new InvalidTokenException(Reason.BAD_AUDIENCE);
        }

        // exp is required: a token that never expires is not an access token.
        Instant expiresAt = jwt.claimInstant("exp").orElseThrow(() -> new InvalidTokenException(Reason.MISSING_CLAIM));
        if (!now.minus(leeway).isBefore(expiresAt)) {
            throw new InvalidTokenException(Reason.EXPIRED);
        }
        Optional<Instant> notBefore = jwt.claimInstant("nbf");
        if (notBefore.isPresent() && now.plus(leeway).isBefore(notBefore.get())) {
            throw new InvalidTokenException(Reason.NOT_YET_VALID);
        }
        Optional<Instant> issuedAt = jwt.claimInstant("iat");
        if (issuedAt.isPresent() && issuedAt.get().isAfter(now.plus(leeway))) {
            throw new InvalidTokenException(Reason.NOT_YET_VALID);
        }

        String subject = jwt.claimString("sub")
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new InvalidTokenException(Reason.MISSING_CLAIM));
        Optional<String> clientId = jwt.claimString("azp").or(() -> jwt.claimString("client_id"));
        return new Principal(subject, tokenIssuer, Scopes.extract(jwt.claims()), clientId, expiresAt);
    }

    private static String jcaName(String algorithm) {
        String digest = "SHA" + algorithm.substring(2);
        return switch (algorithm.substring(0, 2)) {
            case "RS" -> digest + "withRSA";
            case "PS" -> "RSASSA-PSS";
            // JWS ECDSA signatures are the raw r||s pair (IEEE P1363), not the DER SEQUENCE that
            // "SHA256withECDSA" expects. The JDK's P1363 variant consumes that form directly.
            default -> digest + "withECDSAinP1363Format";
        };
    }

    private static PSSParameterSpec pssParameters(String algorithm) {
        String digest = "SHA-" + algorithm.substring(2);
        int saltLength = Integer.parseInt(algorithm.substring(2)) / 8;
        return new PSSParameterSpec(digest, "MGF1", new MGF1ParameterSpec(digest), saltLength, 1);
    }
}
