package com.example.app.security;

import com.example.app.config.AppProperties;
import com.example.app.config.AuthProperties;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * Security wiring. Which chain is built is decided by {@code app.auth.enabled}, which
 * {@code AuthEnabledEnvironmentPostProcessor} derives from {@code APP_AUTH_ISSUER}: off until an
 * identity provider is configured, on the moment one is.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** Answerable without a token: probes, metrics, and the contract that says how to get one. */
    static final String[] PUBLIC_PATHS = {
        "/health/live", "/health/ready", "/metrics", "/openapi.yaml",
        "/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/actuator/info",
    };

    @Bean
    ScopeNames scopeNames(AppProperties properties) {
        return new ScopeNames(properties.auth().scopePrefix());
    }

    @Bean
    RequiredScopes requiredScopes(ScopeNames scopeNames) {
        return new RequiredScopes(scopeNames);
    }

    // ---------------------------------------------------------------- the two chains

    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
    SecurityFilterChain securedFilterChain(HttpSecurity http, AppProperties properties,
            RequiredScopes requiredScopes, AuthMetrics metrics, ObjectMapper mapper,
            JwtDecoder decoder) throws Exception {

        AuthProperties auth = properties.auth();
        var entryPoint = new ProblemAuthenticationEntryPoint(auth.realm(), metrics, mapper);
        var accessDenied = new ProblemAccessDeniedHandler(auth.realm(), requiredScopes, metrics, mapper);

        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    // A CORS preflight carries no Authorization header by definition, so it has to
                    // be answerable without one or no browser could ever reach the API.
                    requests.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    requests.requestMatchers(PUBLIC_PATHS).permitAll();
                    for (RequiredScopes.Rule rule : requiredScopes.rules()) {
                        requests.requestMatchers(rule.method(), rule.pattern().getPatternString())
                                .hasAuthority(rule.scope());
                        if (HttpMethod.GET.equals(rule.method())) {
                            requests.requestMatchers(HttpMethod.HEAD, rule.pattern().getPatternString())
                                    .hasAuthority(rule.scope());
                        }
                    }
                    // The sibling service makes every route state its access level, so a route
                    // added without one does not compile. This is the runtime equivalent: anything
                    // not named above is refused rather than quietly reachable.
                    requests.anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver(auth))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied)
                        // By default the bearer filter rethrows AuthenticationServiceException —
                        // the failure Spring raises when verification could not be carried out at
                        // all, such as an unreachable key server. Rethrowing loses it: the request
                        // simply ends up unauthenticated and the caller is told its token is
                        // missing, which is both wrong and unactionable. Handing it to the entry
                        // point instead is what lets that case answer 503.
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                var handler = new AuthenticationEntryPointFailureHandler(entryPoint);
                                handler.setRethrowAuthenticationServiceException(false);
                                filter.setAuthenticationFailureHandler(handler);
                                return filter;
                            }
                        })
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authenticationConverter())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied));

        applyResponseHeaders(http);
        LOG.atInfo().setMessage("auth.enabled")
                .addKeyValue("issuer", auth.issuer())
                .addKeyValue("audience", auth.audience())
                .addKeyValue("jwksUrl", String.valueOf(auth.jwksUrl()))
                .addKeyValue("scopePrefix", auth.scopePrefix())
                .log();
        return http.build();
    }

    /**
     * No identity provider is configured, so there is nothing to verify a token against and every
     * route is open. The response headers are still applied: they cost nothing and are wanted
     * whether or not callers are authenticated.
     */
    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        applyResponseHeaders(http);
        return http.build();
    }

    /** Warned about on every boot, so an unsecured deployment is never quiet about it. */
    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "false", matchIfMissing = true)
    AuthDisabledWarning authDisabledWarning() {
        return new AuthDisabledWarning();
    }

    static class AuthDisabledWarning {
        @EventListener(ApplicationReadyEvent.class)
        void warn() {
            LOG.warn("auth.disabled");
        }
    }

    // ---------------------------------------------------------------- token verification

    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
    JwtDecoder jwtDecoder(AppProperties properties, JWKSource<SecurityContext> jwkSource,
            MeterRegistry registry) {

        AuthProperties auth = properties.auth();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource)
                // Asymmetric signatures only. Leaving HS* in is what makes the key-confusion
                // attack possible: a public key, which is public, doubles as an HMAC secret.
                .jwsAlgorithms(algorithms -> algorithms.addAll(Set.of(
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS384,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS512,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.PS256,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.PS384,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.PS512,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES384,
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES512)))
                .build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtTypeValidator.jwt()); // RFC 9068: JWT, at+jwt or application/at+jwt
        validators.add(new JwtIssuerValidator(auth.issuer()));
        validators.add(new JwtTimestampValidator(auth.clockLeewaySeconds()));
        validators.add(requiredClaim(JwtClaimNames.EXP, "an expiry"));
        validators.add(requiredClaim(JwtClaimNames.SUB, "a subject"));
        // JwtTimestampValidator covers exp and nbf but not iat, and a token issued in the future
        // is either a clock problem or a forgery.
        validators.add(new JwtClaimValidator<Instant>(JwtClaimNames.IAT,
                issuedAt -> issuedAt == null
                        || !issuedAt.isAfter(Instant.now().plus(auth.clockLeewaySeconds()))));
        if (!auth.audience().isBlank()) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    audience -> audience != null && audience.contains(auth.audience())));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> requiredClaim(String claim, String description) {
        return jwt -> jwt.getClaim(claim) != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                        "invalid_token", "The token must carry " + description, null));
    }

    private static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ScopeAuthoritiesConverter());
        return converter;
    }

    /**
     * Bounds the token before anything tries to parse it. {@code NimbusJwtDecoder} has no size cap
     * of its own, so without this a caller can hand over a megabyte of base64 and have it decoded.
     */
    private static BearerTokenResolver bearerTokenResolver(AuthProperties auth) {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> {
            String token = delegate.resolve(request);
            if (token != null && token.length() > auth.maxTokenBytes()) {
                throw new InvalidBearerTokenException("Bearer token is too large");
            }
            return token;
        };
    }

    // ---------------------------------------------------------------- response headers

    private static void applyResponseHeaders(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp ->
                        csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // TLS terminates at the ingress, so requests arrive as plain HTTP and Spring would
                // otherwise leave the header off; the caller's connection is still HTTPS.
                .httpStrictTransportSecurity(hsts -> hsts
                        .requestMatcher(AnyRequestMatcher.INSTANCE)
                        .maxAgeInSeconds(31_536_000)
                        .includeSubDomains(true)));
        http.cors(Customizer.withDefaults());
    }
}
