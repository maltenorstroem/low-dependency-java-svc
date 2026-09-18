package com.example.app.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.convert.DurationUnit;

/**
 * OAuth2 resource-server settings.
 *
 * <p>Authentication is off until an issuer is configured, and on the moment one is. That rule
 * gives both properties that matter: this service still starts and serves with no configuration at
 * all — which is what makes {@code bootRun} and the container smoke test work — while it is
 * impossible to configure an identity provider and accidentally leave enforcement off. Setting
 * {@code APP_AUTH_ENABLED=true} without the rest is a startup failure, not a silent bypass.
 *
 * <p>{@code enabled} is not defaulted here but derived from {@code APP_AUTH_ISSUER} before binding,
 * by {@link AuthEnabledEnvironmentPostProcessor}, because the security filter chain is selected on
 * it with {@code @ConditionalOnProperty} and conditions are evaluated before this record exists.
 */
public record AuthProperties(

        boolean enabled,

        @NotNull String issuer,

        @NotNull String audience,

        URI jwksUrl,

        @NotNull String scopePrefix,

        @NotNull String realm,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 10) @DurationMax(seconds = 86_400) Duration jwksTtlSeconds,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 1) @DurationMax(seconds = 3_600) Duration jwksMinRefreshSeconds,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 1) @DurationMax(seconds = 60) Duration jwksTimeoutSeconds,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 60) @DurationMax(seconds = 86_400) Duration jwksMaxStaleSeconds,

        @Min(1_024) @Max(1_048_576) int jwksMaxBytes,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 0) @DurationMax(seconds = 300) Duration clockLeewaySeconds,

        @Min(256) @Max(65_536) int maxTokenBytes) {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @AssertTrue(message = "APP_AUTH_ISSUER is required when authentication is enabled")
    boolean isIssuerPresentWhenEnabled() {
        return !enabled || (issuer != null && !issuer.isBlank());
    }

    @AssertTrue(message = "APP_AUTH_JWKS_URL is required when authentication is enabled")
    boolean isJwksUrlPresentWhenEnabled() {
        return !enabled || jwksUrl != null;
    }

    @AssertTrue(message = "APP_AUTH_JWKS_URL must be an absolute URL")
    boolean isJwksUrlAbsolute() {
        return jwksUrl == null || (jwksUrl.isAbsolute() && jwksUrl.getHost() != null);
    }

    /**
     * Signing keys fetched over plain HTTP could be substituted in flight, which would defeat the
     * whole mechanism. The loopback exception exists so the test suite can run a stub key server
     * without a certificate.
     */
    @AssertTrue(message = "APP_AUTH_JWKS_URL must use https (http is allowed only on loopback)")
    boolean isJwksUrlSecure() {
        if (!enabled || jwksUrl == null || jwksUrl.getScheme() == null || jwksUrl.getHost() == null) {
            return true; // other constraints report these
        }
        String scheme = jwksUrl.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("https")
                || (scheme.equals("http") && LOOPBACK.contains(jwksUrl.getHost().toLowerCase(Locale.ROOT)));
    }
}
