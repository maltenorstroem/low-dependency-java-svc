package com.example.app.security;

import com.example.app.http.Access;
import com.example.app.observability.Log;
import com.example.app.security.InvalidTokenException.Reason;
import com.example.app.observability.Metrics;
import java.util.Objects;
import java.util.Optional;

/**
 * The single decision point: given what a route requires and what the caller presented, either
 * return the verified {@link Principal} or throw.
 *
 * <p>Public routes never parse a token at all, so an unauthenticated health check costs nothing.
 */
public final class Authenticator {

    private static final Log LOG = Log.get(Authenticator.class);

    private final JwtVerifier verifier; // null when authentication is disabled
    private final String realm;
    private final int maxTokenBytes;
    private final Metrics metrics;

    private Authenticator(JwtVerifier verifier, String realm, int maxTokenBytes, Metrics metrics) {
        this.verifier = verifier;
        this.realm = realm;
        this.maxTokenBytes = maxTokenBytes;
        this.metrics = metrics;
    }

    /** Every route is served without a token. Used when no identity provider is configured. */
    public static Authenticator disabled() {
        return new Authenticator(null, "api", 0, null);
    }

    public static Authenticator of(JwtVerifier verifier, String realm, int maxTokenBytes, Metrics metrics) {
        // A null verifier here would quietly produce an authenticator that enforces nothing,
        // which is the one mistake this class must not make easy.
        Objects.requireNonNull(verifier, "verifier");
        Objects.requireNonNull(metrics, "metrics");
        return new Authenticator(verifier, sanitiseRealm(realm), maxTokenBytes, metrics);
    }

    public boolean enabled() {
        return verifier != null;
    }

    public String realm() {
        return realm;
    }

    /**
     * @param authorization the raw {@code Authorization} header, or null
     * @return the caller's identity, or empty for a public route or when authentication is off
     * @throws AuthException when the route requires an identity the caller did not establish
     */
    public Optional<Principal> authorize(Access access, String authorization) {
        if (verifier == null || access instanceof Access.Anonymous) {
            return Optional.empty();
        }
        Principal principal;
        try {
            principal = verifier.verify(BearerTokens.extract(authorization, maxTokenBytes));
        } catch (AuthException e) {
            reject(e);
            throw e;
        }
        if (access instanceof Access.RequiresScope required && !principal.hasAllScopes(required.allOf())) {
            InsufficientScopeException e = new InsufficientScopeException(required.allOf());
            reject(e);
            throw e;
        }
        metrics.authDecision("allowed");
        LOG.debug("auth.authenticated", "sub", principal.subject(), "scopeCount", principal.scopes().size());
        return Optional.of(principal);
    }

    /**
     * Debug, not warn: rejections are routine and an attacker must not be able to fill the log by
     * retrying. The counter is the thing to alert on. Only the internal reason is recorded — never
     * the token, and never any part of it.
     */
    private void reject(AuthException e) {
        metrics.authDecision(switch (e) {
            case MissingCredentialsException m -> "missing_token";
            case InsufficientScopeException i -> "insufficient_scope";
            case InvalidTokenException t when t.cause() == Reason.KEYS_UNAVAILABLE -> "keys_unavailable";
            default -> "invalid_token";
        });
        LOG.debug("auth.rejected", "reason", e.reason());
    }

    /** Keeps the realm from breaking the quoted-string syntax of {@code WWW-Authenticate}. */
    private static String sanitiseRealm(String realm) {
        StringBuilder safe = new StringBuilder(realm.length());
        realm.chars()
                .filter(c -> c >= 0x20 && c < 0x7F && c != '"' && c != '\\')
                .forEach(c -> safe.append((char) c));
        return safe.isEmpty() ? "api" : safe.toString();
    }
}
