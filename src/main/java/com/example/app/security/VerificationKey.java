package com.example.app.security;

import java.security.PublicKey;
import java.util.Objects;

/**
 * One usable signing key from a JWKS.
 *
 * <p>{@code algorithm} is the JWS algorithm the <em>key</em> supports, derived from its {@code kty}
 * and {@code crv}. Verification uses this value; the token's {@code alg} header only has to agree
 * with it. That is what makes algorithm confusion — presenting an HMAC token signed with the
 * server's own RSA public key — structurally impossible rather than merely checked for.
 */
public record VerificationKey(String kid, String algorithm, PublicKey key) {

    public VerificationKey {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(key, "key");
    }
}
