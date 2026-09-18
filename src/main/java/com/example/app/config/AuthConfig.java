package com.example.app.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * OAuth2 resource-server settings.
 *
 * <p>Authentication is off until an issuer is configured, and on the moment one is. That rule
 * gives both properties that matter: this service still starts and serves with no configuration at
 * all — which is what makes {@code ./build.sh run} and the container smoke test work — while it is
 * impossible to configure an identity provider and accidentally leave enforcement off. Setting
 * {@code APP_AUTH_ENABLED=true} without the rest is a startup failure, not a silent bypass.
 */
public record AuthConfig(
        boolean enabled,
        String issuer,
        String audience,
        URI jwksUrl,
        String scopePrefix,
        String realm,
        Duration jwksTtl,
        Duration jwksMinRefresh,
        Duration jwksTimeout,
        Duration jwksMaxStale,
        int jwksMaxBytes,
        Duration clockLeeway,
        int maxTokenBytes) {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    public AuthConfig {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(scopePrefix, "scopePrefix");
        Objects.requireNonNull(realm, "realm");
        if (enabled) {
            if (issuer.isBlank()) {
                throw new ConfigException("APP_AUTH_ISSUER is required when authentication is enabled");
            }
            if (jwksUrl == null) {
                throw new ConfigException("APP_AUTH_JWKS_URL is required when authentication is enabled");
            }
            requireSecure(jwksUrl);
        }
    }

    static AuthConfig from(EnvReader r) {
        String issuer = r.string("APP_AUTH_ISSUER", "");
        return new AuthConfig(
                r.bool("APP_AUTH_ENABLED", !issuer.isEmpty()),
                issuer,
                r.string("APP_AUTH_AUDIENCE", ""),
                r.uri("APP_AUTH_JWKS_URL"),
                r.string("APP_AUTH_SCOPE_PREFIX", "task-service:"),
                r.string("APP_AUTH_REALM", "api"),
                r.seconds("APP_AUTH_JWKS_TTL_SECONDS", 300, 10, 86_400),
                r.seconds("APP_AUTH_JWKS_MIN_REFRESH_SECONDS", 30, 1, 3_600),
                r.seconds("APP_AUTH_JWKS_TIMEOUT_SECONDS", 5, 1, 60),
                r.seconds("APP_AUTH_JWKS_MAX_STALE_SECONDS", 3_600, 60, 86_400),
                r.integer("APP_AUTH_JWKS_MAX_BYTES", 131_072, 1_024, 1_048_576),
                r.seconds("APP_AUTH_CLOCK_LEEWAY_SECONDS", 60, 0, 300),
                r.integer("APP_AUTH_MAX_TOKEN_BYTES", 8_192, 256, 65_536));
    }

    /**
     * Signing keys fetched over plain HTTP could be substituted in flight, which would defeat the
     * whole mechanism. The loopback exception exists so the test suite can run a stub key server
     * without a certificate.
     */
    private static void requireSecure(URI url) {
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) {
            return;
        }
        if (scheme.equals("http") && LOOPBACK.contains(url.getHost().toLowerCase(Locale.ROOT))) {
            return;
        }
        throw new ConfigException("APP_AUTH_JWKS_URL must use https (http is allowed only on loopback)");
    }
}
