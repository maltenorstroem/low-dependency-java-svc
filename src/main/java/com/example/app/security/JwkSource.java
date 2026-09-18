package com.example.app.security;

import java.util.Optional;

/**
 * Where verification keys come from. The seam that lets the verifier be tested against an
 * in-memory key, with no HTTP server and no network, and lets the JWKS client be tested on its
 * own.
 */
public interface JwkSource {

    /**
     * @param kid the {@code kid} header of the token, or null when it carries none
     * @return the key, or empty when this source has no such key (after at most one refresh)
     */
    Optional<VerificationKey> find(String kid);

    /** How many usable keys are currently held. */
    int size();
}
