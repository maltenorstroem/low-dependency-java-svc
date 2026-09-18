package com.example.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The same rules {@code AuthConfigTest} pins in the zero-dependency service: defaults, ranges, the
 * off-unless-configured default, and the refusal to fetch signing keys over plain HTTP. Binding
 * failures here are what produce exit code 2 in {@code TaskServiceApplication}.
 */
class AppPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(defaults());

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfiguration {
    }

    private static String[] defaults() {
        return new String[] {
            "app.host=0.0.0.0", "app.port=8080", "app.backlog=0",
            "app.max-connections=1000", "app.max-concurrent-requests=256",
            "app.max-body-bytes=1048576", "app.request-timeout-seconds=30",
            "app.shutdown-grace-seconds=20", "app.drain-delay-seconds=5",
            "app.max-tasks=100000", "app.max-cubes=100000",
            "app.idempotency-ttl-seconds=86400", "app.max-idempotency-keys=10000",
            "app.auth.enabled=false", "app.auth.issuer=", "app.auth.audience=",
            "app.auth.scope-prefix=task-service:", "app.auth.realm=api",
            "app.auth.jwks-ttl-seconds=300", "app.auth.jwks-min-refresh-seconds=30",
            "app.auth.jwks-timeout-seconds=5", "app.auth.jwks-max-stale-seconds=3600",
            "app.auth.jwks-max-bytes=131072", "app.auth.clock-leeway-seconds=60",
            "app.auth.max-token-bytes=8192",
        };
    }

    @Test
    void bindsDocumentedDefaults() {
        runner.run(context -> {
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.host()).isEqualTo("0.0.0.0");
            assertThat(properties.port()).isEqualTo(8080);
            assertThat(properties.maxBodyBytes()).isEqualTo(1_048_576);
            assertThat(properties.maxConcurrentRequests()).isEqualTo(256);
            assertThat(properties.requestTimeoutSeconds()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.drainDelaySeconds()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.idempotencyTtlSeconds()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.auth().enabled()).isFalse();
            assertThat(properties.auth().scopePrefix()).isEqualTo("task-service:");
            assertThat(properties.auth().realm()).isEqualTo("api");
            assertThat(properties.auth().clockLeewaySeconds()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void readsEnvironmentStyleOverrides() {
        runner.withPropertyValues("app.max-body-bytes=2048", "app.port=9090")
                .run(context -> {
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertThat(properties.maxBodyBytes()).isEqualTo(2048);
                    assertThat(properties.port()).isEqualTo(9090);
                });
    }

    @Test
    void rejectsValuesOutOfRange() {
        runner.withPropertyValues("app.port=70000").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("app.max-body-bytes=512").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("app.request-timeout-seconds=0").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("app.auth.max-token-bytes=1").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("app.auth.jwks-ttl-seconds=1").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnparseableValues() {
        runner.withPropertyValues("app.port=eight").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void acceptsCompleteAuthConfiguration() {
        runner.withPropertyValues(
                        "app.auth.enabled=true",
                        "app.auth.issuer=https://idp.example.com",
                        "app.auth.jwks-url=https://idp.example.com/keys")
                .run(context -> {
                    AuthProperties auth = context.getBean(AppProperties.class).auth();
                    assertThat(auth.enabled()).isTrue();
                    assertThat(auth.issuer()).isEqualTo("https://idp.example.com");
                    assertThat(auth.jwksUrl()).isEqualTo(URI.create("https://idp.example.com/keys"));
                });
    }

    @Test
    void refusesIncompleteAuthConfiguration() {
        runner.withPropertyValues("app.auth.enabled=true").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("app.auth.enabled=true", "app.auth.issuer=https://idp.example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesSigningKeysOverPlainHttp() {
        runner.withPropertyValues(
                        "app.auth.enabled=true",
                        "app.auth.issuer=https://idp.example.com",
                        "app.auth.jwks-url=http://idp.example.com/keys")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void allowsPlainHttpOnLoopbackSoTheTestSuiteNeedsNoCertificate() {
        for (String host : new String[] {"localhost", "127.0.0.1"}) {
            runner.withPropertyValues(
                            "app.auth.enabled=true",
                            "app.auth.issuer=https://idp.example.com",
                            "app.auth.jwks-url=http://" + host + ":9999/jwks")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void refusesMalformedJwksUrl() {
        runner.withPropertyValues(
                        "app.auth.enabled=true",
                        "app.auth.issuer=https://idp.example.com",
                        "app.auth.jwks-url=not-a-url")
                .run(context -> assertThat(context).hasFailed());
    }
}
