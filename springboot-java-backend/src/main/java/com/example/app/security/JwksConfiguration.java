package com.example.app.security;

import com.example.app.config.AppProperties;
import com.example.app.config.AuthProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The JWKS source: how signing keys are fetched, cached and given up on.
 *
 * <p>Nimbus supplies the caching; what it does not supply, and what has to be built here, is the
 * refusal to follow redirects. A JWKS URL that redirects is an SSRF primitive — the service will
 * fetch whatever it is pointed at and then trust the keys it finds — so the retriever below uses a
 * client that will not follow one, and {@link AuthProperties} has already refused a URL that is not
 * https or loopback.
 *
 * <p>The cache is deliberately forgiving of a brief outage and then firm: keys are served past
 * their TTL for up to {@code jwksMaxStale}, because an identity provider being briefly unreachable
 * should not take this service down with it, and after that verification fails closed rather than
 * accepting tokens it cannot check. A token with an unknown key id triggers a refresh, rate-limited
 * so that unknown ids cannot be used to hammer the provider.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class JwksConfiguration {

    @Bean
    JWKSource<SecurityContext> jwkSource(AppProperties properties, MeterRegistry registry) {
        AuthProperties auth = properties.auth();
        ResourceRetriever retriever = new RedirectRefusingRetriever(
                auth.jwksTimeoutSeconds(), auth.jwksMaxBytes());

        JWKSource<SecurityContext> source = JWKSourceBuilder
                .create(toUrl(auth), retriever)
                .cache(auth.jwksTtlSeconds().toMillis(), auth.jwksMinRefreshSeconds().toMillis())
                .refreshAheadCache(false)
                .rateLimited(auth.jwksMinRefreshSeconds().toMillis())
                .outageTolerant(auth.jwksMaxStaleSeconds().toMillis())
                .build();

        return countingKeys(source, registry);
    }

    /**
     * Publishes how many keys are cached. Spring and Nimbus expose no equivalent, and without it an
     * identity provider that starts serving an empty key set looks exactly like one that is fine
     * until every token starts failing.
     */
    private static JWKSource<SecurityContext> countingKeys(JWKSource<SecurityContext> delegate,
            MeterRegistry registry) {

        AtomicInteger keys = new AtomicInteger();
        registry.gauge("auth_jwks_keys", keys);
        return (selector, context) -> {
            List<JWK> jwks = delegate.get(selector, context);
            keys.set(jwks.size());
            return jwks;
        };
    }

    private static URL toUrl(AuthProperties auth) {
        try {
            return auth.jwksUrl().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("APP_AUTH_JWKS_URL is not a usable URL", e);
        }
    }

    /** Fetches the key document over a client that will not follow a redirect, and caps its size. */
    private record RedirectRefusingRetriever(Duration timeout, int maxBytes) implements ResourceRetriever {

        @Override
        public Resource retrieveResource(URL url) throws IOException {
            try (HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(timeout)
                    .build()) {

                HttpRequest request = HttpRequest.newBuilder(url.toURI())
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("Key server answered " + response.statusCode());
                }
                byte[] body = response.body();
                if (body.length > maxBytes) {
                    throw new IOException("Key document exceeds " + maxBytes + " bytes");
                }
                return new Resource(new String(body, java.nio.charset.StandardCharsets.UTF_8),
                        response.headers().firstValue("Content-Type").orElse("application/json"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching keys", e);
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Unusable key server URL", e);
            }
        }
    }
}
