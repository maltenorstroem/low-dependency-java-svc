package com.example.app.config;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and range-checks one environment variable at a time. Shared by {@link Config} and
 * {@link AuthConfig} so both fail the same way, at startup, naming the offending key.
 */
record EnvReader(Map<String, String> env) {

    String string(String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    int integer(String key, int fallback, int min, int max) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be an integer");
        }
        if (value < min || value > max) {
            throw new ConfigException(key + " must be between " + min + " and " + max);
        }
        return value;
    }

    Duration seconds(String key, int fallback, int min, int max) {
        return Duration.ofSeconds(integer(key, fallback, min, max));
    }

    Level level(String key, Level fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Level.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(key + " must be one of TRACE, DEBUG, INFO, WARNING, ERROR, OFF");
        }
    }

    boolean bool(String key, boolean fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new ConfigException(key + " must be true or false");
        };
    }

    /** @return null when the variable is unset, so the caller decides whether that is fatal. */
    URI uri(String key) {
        String raw = string(key, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(raw);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new ConfigException(key + " must be an absolute URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ConfigException(key + " must be a valid URL");
        }
    }
}
