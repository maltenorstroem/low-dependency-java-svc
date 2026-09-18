package com.example.app.config;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, fully validated configuration read from environment variables (12-factor, factor
 * III). Every value has a safe default and a hard range, so a typo fails at startup instead of at
 * 3 a.m.
 */
public record Config(
        String host,
        int port,
        int backlog,
        int maxConnections,
        int maxConcurrentRequests,
        int maxBodyBytes,
        Duration requestTimeout,
        Duration shutdownGrace,
        Duration drainDelay,
        Level logLevel,
        int maxTasks,
        int maxCubes,
        Duration idempotencyTtl,
        int maxIdempotencyKeys,
        AuthConfig auth) {

    public Config {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        Objects.requireNonNull(drainDelay, "drainDelay");
        Objects.requireNonNull(logLevel, "logLevel");
        Objects.requireNonNull(idempotencyTtl, "idempotencyTtl");
        Objects.requireNonNull(auth, "auth");
    }

    public static Config fromEnvironment(Map<String, String> env) {
        EnvReader r = new EnvReader(env);
        return new Config(
                r.string("APP_HOST", "0.0.0.0"),
                r.integer("APP_PORT", 8080, 0, 65_535),
                r.integer("APP_BACKLOG", 0, 0, 65_535),
                r.integer("APP_MAX_CONNECTIONS", 1_000, 1, 1_000_000),
                r.integer("APP_MAX_CONCURRENT_REQUESTS", 256, 1, 100_000),
                r.integer("APP_MAX_BODY_BYTES", 1_048_576, 1_024, 64 * 1_048_576),
                r.seconds("APP_REQUEST_TIMEOUT_SECONDS", 30, 1, 3_600),
                r.seconds("APP_SHUTDOWN_GRACE_SECONDS", 20, 1, 600),
                r.seconds("APP_DRAIN_DELAY_SECONDS", 5, 0, 600),
                r.level("APP_LOG_LEVEL", Level.INFO),
                r.integer("APP_MAX_TASKS", 100_000, 1, 100_000_000),
                r.integer("APP_MAX_CUBES", 100_000, 1, 100_000_000),
                r.seconds("APP_IDEMPOTENCY_TTL_SECONDS", 86_400, 1, 7 * 86_400),
                r.integer("APP_MAX_IDEMPOTENCY_KEYS", 10_000, 1, 10_000_000),
                AuthConfig.from(r));
    }
}
