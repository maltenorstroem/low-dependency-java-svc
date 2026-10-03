package com.example.app.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.convert.DurationUnit;

/**
 * Settings for the {@code /v1/nautical-flags} stream.
 *
 * <p>A caller chooses its own interval, but only within {@code [minIntervalMillis,
 * maxIntervalMillis]}: without a floor one client asking for an event every millisecond costs as
 * much as a thousand polite ones. The text length, duration and stream caps exist because a stream
 * outlives the request that opened it, so neither the request timeout nor load shedding bounds it.
 */
public record NauticalFlagsProperties(

        @Min(1) @Max(3_600_000) long defaultIntervalMillis,

        @Min(1) @Max(3_600_000) long minIntervalMillis,

        @Min(1) @Max(3_600_000) long maxIntervalMillis,

        @Min(1) @Max(4_096) int maxTextLength,

        @DurationUnit(ChronoUnit.SECONDS)
        @DurationMin(seconds = 1) @DurationMax(seconds = 86_400) Duration maxDurationSeconds,

        @Min(1) @Max(100_000) int maxConcurrentStreams) {

    @AssertTrue(message = "APP_NAUTICAL_FLAGS_DEFAULT_INTERVAL_MILLIS must lie between the minimum and maximum interval")
    boolean isDefaultIntervalWithinBounds() {
        return minIntervalMillis <= defaultIntervalMillis && defaultIntervalMillis <= maxIntervalMillis;
    }
}
