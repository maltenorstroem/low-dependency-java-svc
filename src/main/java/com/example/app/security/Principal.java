package com.example.app.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The verified identity behind a request. Named for the OAuth2 subject rather than a "user":
 * in the client-credentials flow that identity is a machine, not a person.
 *
 * <p>Deliberately holds no part of the token it came from, so there is nothing here that could
 * leak into a log line or a response body.
 */
public record Principal(
        String subject,
        String issuer,
        Set<String> scopes,
        Optional<String> clientId,
        Instant expiresAt) {

    public Principal {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        scopes = Set.copyOf(scopes);
    }

    public boolean hasAllScopes(Set<String> required) {
        return scopes.containsAll(required);
    }
}
