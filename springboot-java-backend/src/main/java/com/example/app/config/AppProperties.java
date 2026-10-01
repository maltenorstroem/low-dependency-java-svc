package com.example.app.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration, read from the same {@code APP_*} environment variables the zero-dependency
 * service uses (12-factor, factor III). Every value keeps its default and its hard range, so a
 * typo still fails at startup rather than at 3 a.m.
 *
 * <p>The property names deliberately keep the {@code ...Seconds} suffix even though the type is a
 * {@link Duration}: relaxed binding maps {@code APP_REQUEST_TIMEOUT_SECONDS} onto
 * {@code app.request-timeout-seconds}, and keeping the variable names identical is what lets one
 * deployment manifest and one smoke script drive either implementation.
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(

        @NotBlank String host,

        @Min(0) @Max(65_535) int port,

        @Min(0) @Max(65_535) int backlog,

        @Min(1) @Max(1_000_000) int maxConnections,

        @Min(1) @Max(100_000) int maxConcurrentRequests,

        @Min(1_024) @Max(64 * 1_048_576) int maxBodyBytes,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 1) @DurationMax(seconds = 3_600) Duration requestTimeoutSeconds,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 1) @DurationMax(seconds = 600) Duration shutdownGraceSeconds,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 0) @DurationMax(seconds = 600) Duration drainDelaySeconds,

        @Min(1) @Max(100_000_000) int maxTasks,

        @Min(1) @Max(100_000_000) int maxCubes,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 1) @DurationMax(days = 7) Duration idempotencyTtlSeconds,

        @Min(1) @Max(10_000_000) int maxIdempotencyKeys,

        @NotNull @Valid AuthProperties auth,

        @NotNull @Valid RandomStreamProperties randomStream) {
}
